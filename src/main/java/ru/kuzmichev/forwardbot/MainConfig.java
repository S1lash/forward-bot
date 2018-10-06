package ru.kuzmichev.forwardbot;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import ru.kuzmichev.forwardbot.configuration.TelegramConfig;
import ru.kuzmichev.forwardbot.configuration.VkConfig;

@SpringBootApplication(scanBasePackages = "ru.kuzmichev")
@Import({
        TelegramConfig.class,
        VkConfig.class
})
public class MainConfig {

}
