package com.mautini.assistant.demo;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;
import com.mautini.assistant.demo.api.AssistantClient;
import com.mautini.assistant.demo.authentication.AuthenticationHelper;
import com.mautini.assistant.demo.config.AssistantConf;
import com.mautini.assistant.demo.config.AuthenticationConf;
import com.mautini.assistant.demo.config.DeviceRegisterConf;
import com.mautini.assistant.demo.config.IoConf;
import com.mautini.assistant.demo.device.DeviceRegister;
import com.mautini.assistant.demo.exception.AuthenticationException;
import com.mautini.assistant.demo.exception.ConverseException;
import com.mautini.assistant.demo.exception.DeviceRegisterException;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigBeanFactory;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;

public class GoogleAssistantClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleAssistantClient.class);
    private static final String CHECK_TV_ON = "Is the roop google TV on";
    private static final String SWITCH_ON = "Switch on LED light";
    private static final String SWITCH_OFF = "Switch off LED light";
    private static final int hour6 = 12;
    private static final int nextDayHour2 = 26;
    private static final Location location = new Location("38.631798", "-121.213416");
    private static final SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(location, "America/Los_Angeles");
    public static void main(String[] args) throws AuthenticationException, ConverseException, DeviceRegisterException, InterruptedException {

        Config root = ConfigFactory.load();
        AuthenticationConf authenticationConf = ConfigBeanFactory.create(root.getConfig("authentication"), AuthenticationConf.class);
        authenticationConf.setClientId(System.getenv("clientId"));
        authenticationConf.setClientSecret(System.getenv("secret"));
        DeviceRegisterConf deviceRegisterConf = ConfigBeanFactory.create(root.getConfig("deviceRegister"), DeviceRegisterConf.class);
        AssistantConf assistantConf = ConfigBeanFactory.create(root.getConfig("assistant"), AssistantConf.class);
        IoConf ioConf = ConfigBeanFactory.create(root.getConfig("io"), IoConf.class);

        // Authentication
        AuthenticationHelper authenticationHelper = new AuthenticationHelper(authenticationConf);
        authenticationHelper
                .authenticate()
                .orElseThrow(() -> new AuthenticationException("Error during authentication"));

        // Check if we need to refresh the access token to request the api
        if (authenticationHelper.expired()) {
            authenticationHelper
                    .refreshAccessToken()
                    .orElseThrow(() -> new AuthenticationException("Error refreshing access token"));
        }

        // Register Device model and device
        DeviceRegister deviceRegister = new DeviceRegister(deviceRegisterConf, authenticationHelper.getOAuthCredentials().getAccessToken());
        deviceRegister.register();

        // Build the client (stub)
        AssistantClient assistantClient = new AssistantClient(authenticationHelper.getOAuthCredentials(), assistantConf,
                deviceRegister.getDeviceModel(), deviceRegister.getDevice(), ioConf);

        boolean isOn = false;
        // Main loop
        while (true) {
            Instant instant = Instant.now();
            LocalDateTime local =  LocalDateTime.now();
            LocalDateTime sixPM = LocalDateTime.from(local.truncatedTo(ChronoUnit.HOURS).plus(hour6, ChronoUnit.HOURS));
            LocalDateTime nextDay2am= LocalDateTime.from(local.truncatedTo(ChronoUnit.HOURS).plus(nextDayHour2, ChronoUnit.HOURS));
            if (local.isBefore(sixPM)
                    || local.isAfter(nextDay2am)) {
                //switch off light
                assistantClient.requestAssistant(SWITCH_OFF.getBytes());
                String actionResponse = assistantClient.getTextResponse();
                LOGGER.info(actionResponse);
                if (actionResponse!=null && actionResponse.toLowerCase().contains("off")) {
                    isOn = false;
                }
                Thread.sleep(60 * 60 * 1000);
                continue;
            }
            assistantClient.requestAssistant(CHECK_TV_ON.getBytes());
            String response =  assistantClient.getTextResponse();
            LOGGER.info(response);
            boolean isTVon = response != null && response.toLowerCase().contains("yes");

            // Check if we need to refresh the access token to request the api
            if (authenticationHelper.expired()) {
                authenticationHelper
                        .refreshAccessToken()
                        .orElseThrow(() -> new AuthenticationException("Error refreshing access token"));

                // Update the token for the assistant client
                assistantClient.updateCredentials(authenticationHelper.getOAuthCredentials());
            }

            boolean isSunset = isAfterSunset(instant);

            if(isSunset){
                if (isTVon) {
                    if (isOn) {
                        //there is nothing to do
                        Thread.sleep(15 * 60 * 1000);
                    } else {
                        // flip
                        assistantClient.requestAssistant(SWITCH_ON.getBytes());
                        String actionResponse = assistantClient.getTextResponse();
                        LOGGER.info(actionResponse);
                        if(actionResponse.toLowerCase().contains("on")){
                            isOn = true;
                        }
                    }
                }else {
                    if(isOn){
                        assistantClient.requestAssistant(SWITCH_OFF.getBytes());
                        String actionResponse = assistantClient.getTextResponse();
                        LOGGER.info(actionResponse);
                        if(actionResponse.toLowerCase().contains("off")){
                            isOn = false;
                        }
                    }else{
                        // there is nothing to do here.
                        Thread.sleep(15 * 60 * 1000);
                    }
                }

            }
        }
    }
    private static boolean isAfterSunset(Instant instant) {
        Instant sunSetInstant = calculator.getCivilSunsetCalendarForDate(Calendar.getInstance()).toInstant();
        Instant sunriseInstant = calculator.getCivilSunriseCalendarForDate(Calendar.getInstance()).toInstant();
        boolean isAfterSunset =instant.isAfter(sunSetInstant.plus(30, ChronoUnit.MINUTES)) && Instant.now().isBefore(sunriseInstant);
        LOGGER.info("Light is supposed to be switched {}", isAfterSunset ? "ON" : "OFF");
        return isAfterSunset;
    }
}
