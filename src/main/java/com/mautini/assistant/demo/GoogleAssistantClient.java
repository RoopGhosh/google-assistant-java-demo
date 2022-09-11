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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;

public class GoogleAssistantClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleAssistantClient.class);
    private static final String CHECK_TV_ON = "Is the roop google TV switched on";
    private static final String SWITCH_ON = "Switch on TV light";
    private static final String SWITCH_OFF = "Switch off TV light";
    private static final int hour6 = 18;
    private static final int nextDayHour2 = 26;
    private static final Location location = new Location("38.631798", "-121.213416");
    private static final SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(location, "America/Los_Angeles");
    private final Config root = ConfigFactory.load();
    private final AuthenticationHelper authenticationHelper;
    private boolean override = false;

    public GoogleAssistantClient() throws AuthenticationException {

        AuthenticationConf authenticationConf = ConfigBeanFactory.create(root.getConfig("authentication"), AuthenticationConf.class);
        authenticationConf.setClientId(System.getenv("clientId"));
        authenticationConf.setClientSecret(System.getenv("secret"));
        // Authentication
        authenticationHelper = new AuthenticationHelper(authenticationConf);
        authenticationHelper
                .authenticate()
                .orElseThrow(() -> new AuthenticationException("Error during authentication"));

        // Check if we need to refresh the access token to request the api
        if (authenticationHelper.expired()) {
            authenticationHelper
                    .refreshAccessToken()
                    .orElseThrow(() -> new AuthenticationException("Error refreshing access token"));
        }
    }

    public void scheduledMethod() throws DeviceRegisterException, ConverseException, AuthenticationException, InterruptedException {
        DeviceRegisterConf deviceRegisterConf = ConfigBeanFactory.create(root.getConfig("deviceRegister"), DeviceRegisterConf.class);
        AssistantConf assistantConf = ConfigBeanFactory.create(root.getConfig("assistant"), AssistantConf.class);
        IoConf ioConf = ConfigBeanFactory.create(root.getConfig("io"), IoConf.class);

        // Register Device model and device
        DeviceRegister deviceRegister = new DeviceRegister(deviceRegisterConf, authenticationHelper.getOAuthCredentials().getAccessToken());
        deviceRegister.register();

        // Build the client (stub)
        AssistantClient assistantClient = new AssistantClient(authenticationHelper.getOAuthCredentials(), assistantConf,
                deviceRegister.getDeviceModel(), deviceRegister.getDevice(), ioConf);

        // Check if we need to refresh the access token to request the api
        if (authenticationHelper.expired()) {
            authenticationHelper
                    .refreshAccessToken()
                    .orElseThrow(() -> new AuthenticationException("Error refreshing access token"));

            // Update the token for the assistant client
            assistantClient.updateCredentials(authenticationHelper.getOAuthCredentials());
        }

        assistantClient.requestAssistant(CHECK_TV_ON.getBytes());
        String response = assistantClient.getTextResponse();
        LOGGER.info(response);
        boolean isTVon = response != null && response.toLowerCase().contains("on");

        ZonedDateTime local =  LocalDateTime.now().atZone(ZoneId.of("America/Los_Angeles"));
        boolean isSunset = isAfterSunset(local);

        if (isSunset) {
            if (isTVon) {
                assistantClient.requestAssistant(SWITCH_ON.getBytes());
                String actionResponse = assistantClient.getTextResponse();
                LOGGER.info(actionResponse);
                if (actionResponse.toLowerCase().contains("on")) {
                    //  way to override
                }
            } else {
                assistantClient.requestAssistant(SWITCH_OFF.getBytes());
                String actionResponse = assistantClient.getTextResponse();
                LOGGER.info(actionResponse);
            }
        } else {
            //nothing to do.
            LOGGER.info("Its not sunset. Sleeping");
        }
        assistantClient.getChannel().shutdownNow();
    }


    private boolean isAfterSunset(ZonedDateTime localDateTime) {
        final ZonedDateTime sunSetInstant = ZonedDateTime.ofInstant(calculator.getCivilSunsetCalendarForDate(Calendar.getInstance())
                .toInstant(), ZoneId.of("America/Los_Angeles"));
        final ZonedDateTime sunriseInstant = ZonedDateTime.ofInstant(calculator.getCivilSunriseCalendarForDate(Calendar.getInstance())
                .toInstant().plus(1, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS)
                .plus(3, ChronoUnit.HOURS), ZoneId.of("America/Los_Angeles"));
        boolean isAfterSunset = localDateTime.isAfter(sunSetInstant.plus(30, ChronoUnit.MINUTES)) && localDateTime.isBefore(sunriseInstant);
        LOGGER.info("Light is supposed to be switched {}", isAfterSunset ? "ON" : "OFF");
        return isAfterSunset;
    }
}
