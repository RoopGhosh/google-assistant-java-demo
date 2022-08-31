package com.mautini.assistant.demo.api;

import com.google.assistant.embedded.v1alpha2.*;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.protobuf.ByteString;
import com.mautini.assistant.demo.authentication.OAuthCredentials;
import com.mautini.assistant.demo.config.AssistantConf;
import com.mautini.assistant.demo.config.IoConf;
import com.mautini.assistant.demo.device.Device;
import com.mautini.assistant.demo.device.DeviceModel;
import com.mautini.assistant.demo.exception.ConverseException;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class AssistantClient implements StreamObserver<AssistResponse> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssistantClient.class);

    private CountDownLatch finishLatch = new CountDownLatch(1);

    private EmbeddedAssistantGrpc.EmbeddedAssistantStub embeddedAssistantStub;
    // See reference.conf
    private final AssistantConf assistantConf;

    // if text inputType, text query is set
    private String textQuery;

    private String textResponse;

    private final IoConf ioConf;

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Conversation state to continue a conversation if needed
     *
     * @see <a href="https://developers.google.com/assistant/sdk/reference/rpc/google.assistant.embedded.v1alpha2#google.assistant.embedded.v1alpha2.DialogStateOut.FIELDS.bytes.google.assistant.embedded.v1alpha2.DialogStateOut.conversation_state">Google documentation</a>
     */
    private ByteString currentConversationState;

    private final DeviceModel deviceModel;

    private final Device device;

    private ManagedChannel channel;

    public AssistantClient(OAuthCredentials oAuthCredentials, AssistantConf assistantConf, DeviceModel deviceModel,
                           Device device, IoConf ioConf) {

        this.assistantConf = assistantConf;
        this.deviceModel = deviceModel;
        this.device = device;
        this.currentConversationState = ByteString.EMPTY;
        this.ioConf = ioConf;

        // Create a channel to the test service.
        channel = ManagedChannelBuilder.forAddress(assistantConf.getAssistantApiEndpoint(), 443)
                .build();

        // Create a stub with credential
        embeddedAssistantStub = EmbeddedAssistantGrpc.newStub(channel);

        updateCredentials(oAuthCredentials);
    }

    /**
     * Get CallCredentials from OAuthCredentials
     *
     * @param oAuthCredentials the credentials from the AuthenticationHelper
     * @return the CallCredentials for the GRPC requests
     */
    private CallCredentials getCallCredentials(OAuthCredentials oAuthCredentials) {

        AccessToken accessToken = new AccessToken(
                oAuthCredentials.getAccessToken(),
                new Date(oAuthCredentials.getExpirationTime())
        );

        OAuth2Credentials oAuth2Credentials = OAuth2Credentials.newBuilder()
                .setAccessToken(accessToken)
                .build();

        // Create an instance of {@link io.grpc.CallCredentials}
        return MoreCallCredentials.from(oAuth2Credentials);
    }

    /**
     * Update the credentials used to request the api
     *
     * @param oAuthCredentials the new credentials
     */
    public void updateCredentials(OAuthCredentials oAuthCredentials) {
        embeddedAssistantStub = embeddedAssistantStub.withCallCredentials(getCallCredentials(oAuthCredentials));
    }

    /**
     * Calling text query or audio assistant based on params
     *
     * @param request the request for the assistant (text or voice)
     */
    public void requestAssistant(byte[] request) throws ConverseException {
        textResponse = null;
        switch (ioConf.getInputMode()) {
            case IoConf.TEXT:
                 textRequestAssistant(request);
                break;
            default:
                LOGGER.error("Unknown input mode {}", ioConf.getInputMode());
        }
    }

    /**
     * Handle text query
     *
     * @param request byte[]
     * @return byte[]
     */
    private void textRequestAssistant(byte[] request) throws ConverseException {
        this.textQuery = new String(request);
        try {
            finishLatch = new CountDownLatch(1);
            // Send the config request
            StreamObserver<AssistRequest> requester = embeddedAssistantStub.assist(this);

            requester.onNext(getConfigRequest());

            LOGGER.info("Requesting the assistant {}", textQuery);


            final Integer[] totalTries = {0};
            totalTries[0]= 5;
            final Integer[] count = {0};
            Future<?> future = executor.submit(() -> {
                while (textResponse == null && count[0] < totalTries[0]) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    count[0]++;
                    LOGGER.debug("Tried {} times", count[0]);
                }
                if (textResponse == null) {
                    LOGGER.warn("Did not receive any text response");
                } else {
                    LOGGER.info("Seeing text response {}", textResponse);
                }
            });

            future.get();
            // Mark the end of requests
            requester.onCompleted();

            // Receiving happens asynchronously
            boolean expired = finishLatch.await(1, TimeUnit.MINUTES);
            if (expired) {
                LOGGER.debug("Waited too much time for the response, It could return bad result");
            }

        } catch (Exception e) {
            throw new ConverseException("Error requesting the assistant", e);
        }
    }

    public String getTextResponse() {
        return textResponse;
    }

    @Override
    public void onNext(AssistResponse value) {
        if(value.getEventType() == AssistResponse.EventType.END_OF_UTTERANCE){
            LOGGER.info("Event type : {}", value.getEventType().name());
        }
        try {
            if (value.getEventType() != AssistResponse.EventType.EVENT_TYPE_UNSPECIFIED) {

                LOGGER.info("Event type : {}", value.getEventType().name());
            }

            //currentResponse.write(value.getDialogStateOut().getSupplementalDisplayText().getBytes());
            currentConversationState = value.getDialogStateOut().getConversationState();

            String userRequest = value.getSpeechResultsList().stream()
                    .map(SpeechRecognitionResult::getTranscript)
                    .collect(Collectors.joining(" "));

            if (!userRequest.isEmpty()) {
                LOGGER.info("Request Text : {}", userRequest);
            }

            value.getDialogStateOut().getSupplementalDisplayText();
            if (!value.getScreenOut().getData().isEmpty()) {
                String completeString = value.getScreenOut().getData().toString(StandardCharsets.US_ASCII).toLowerCase();
                String constant = "<div class=\"show_text_container\"> <div> <div class=\"show_text_content\">";
                if (completeString.contains(constant)) {
                    int startIndex = completeString.split(constant)[0].lastIndexOf(">");
                    textResponse = completeString.substring(constant.length()+startIndex + 1,
                            completeString.indexOf("<", constant.length()+startIndex));
                    LOGGER.info("SEEING {}",this.textResponse);
                }
            }

        } catch (Exception e) {
            LOGGER.warn("Error requesting the assistant", e);
        }
    }

    @Override
    public void onError(Throwable t) {
        LOGGER.warn("Error requesting the assistant", t);
        finishLatch.countDown();
    }

    @Override
    public void onCompleted() {
        LOGGER.info("End of the response");
        if(textResponse!=null && textResponse.isEmpty()){
            textResponse = "NO_RESPONSE";
        }
        finishLatch.countDown();
    }

    /**
     * Create the config message, this message must be send before the audio for each request
     *
     * @return the request to send
     */
    private AssistRequest getConfigRequest() {
        AudioInConfig audioInConfig = AudioInConfig
                .newBuilder()
                .setEncoding(AudioInConfig.Encoding.LINEAR16)
                .setSampleRateHertz(assistantConf.getAudioSampleRate())
                .build();

        AudioOutConfig audioOutConfig = AudioOutConfig
                .newBuilder()
                .setEncoding(AudioOutConfig.Encoding.LINEAR16)
                .setSampleRateHertz(assistantConf.getAudioSampleRate())
                .setVolumePercentage(assistantConf.getVolumePercent())
                .build();

        DialogStateIn.Builder dialogStateInBuilder = DialogStateIn
                .newBuilder()
                // We set the us local as default
                .setLanguageCode("en-UK")
                .setConversationState(currentConversationState);

        DeviceConfig deviceConfig = DeviceConfig
                .newBuilder()
                .setDeviceModelId(deviceModel.getDeviceModelId())
                .setDeviceId(device.getId())
                .build();

        ScreenOutConfig screenOutConfig = ScreenOutConfig.newBuilder()
                .setScreenMode(ScreenOutConfig.ScreenMode.PLAYING).build();

        AssistConfig.Builder assistConfigBuilder = AssistConfig
                .newBuilder()
                .setDialogStateIn(dialogStateInBuilder.build())
                .setDeviceConfig(deviceConfig)
                .setAudioInConfig(audioInConfig)
                .setScreenOutConfig(screenOutConfig)
                .setAudioOutConfig(audioOutConfig);

        // Preparing AssistantConfig based on type of input. ie audio or text
        assistConfigBuilder = getAssistConfigBuilder(
                assistConfigBuilder, audioInConfig, textQuery
        );

        return AssistRequest
                .newBuilder()
                .setConfig(assistConfigBuilder.build())
                .build();
    }

    /**
     * Prepares AssistConfig based on input type
     *
     * @param assistConfigBuilder AssistConfig.Builder
     * @param audioConfig         AudioInConfig
     * @param text_query          String
     * @return AssistConfig.Builder
     */
    private AssistConfig.Builder getAssistConfigBuilder(
            AssistConfig.Builder assistConfigBuilder,
            AudioInConfig audioConfig,
            String text_query
    ) {
        switch (ioConf.getInputMode()) {
            case IoConf.AUDIO:
                return assistConfigBuilder.setAudioInConfig(audioConfig);
            case IoConf.TEXT:
                return assistConfigBuilder.setTextQuery(text_query);
            default:
                LOGGER.error("Unknown input mode {}", ioConf.getInputMode());
                return assistConfigBuilder;
        }

    }

    public ManagedChannel getChannel() {
        return channel;
    }
}
