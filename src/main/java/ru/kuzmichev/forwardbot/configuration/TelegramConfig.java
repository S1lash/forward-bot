package ru.kuzmichev.forwardbot.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.kuzmichev.forwardbot.telegram.Bot;

@Configuration
public class TelegramConfig {

    @Bean
    public Bot bot() {
        return new Bot();
    }
}
