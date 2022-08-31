package ghosh.roop.googleassistantstarter;

import com.mautini.assistant.demo.GoogleAssistantClient;
import com.mautini.assistant.demo.exception.AuthenticationException;
import com.mautini.assistant.demo.exception.ConverseException;
import com.mautini.assistant.demo.exception.DeviceRegisterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Arrays;

@Configuration
@EnableScheduling
public class CommandLineAppStartupRunner{
    private static final Logger logger = LoggerFactory.getLogger(CommandLineAppStartupRunner.class);
    GoogleAssistantClient googleAssistantClient;
    public void run(String...args) throws Exception {
        logger.info("Application started with command-line arguments: {} . \n To kill this application, press Ctrl + C.", Arrays.toString(args));
    }

    @Scheduled(cron = "0 0/01 12-23 * * ?", zone = "America/Los_Angeles")
    public void test() {
        try {
            if (googleAssistantClient == null) {
                googleAssistantClient = new GoogleAssistantClient();
            }
            logger.info("Starting scheduled message");
            googleAssistantClient.scheduledMethod();
        } catch (DeviceRegisterException | ConverseException | AuthenticationException | InterruptedException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            // nothing to do..
        }

    }
}