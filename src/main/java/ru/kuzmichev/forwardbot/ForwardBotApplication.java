package ru.kuzmichev.forwardbot;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.telegram.telegrambots.ApiContextInitializer;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import ru.kuzmichev.forwardbot.telegram.Bot;

public class ForwardBotApplication {

	public static void main(String[] args) {
		ApiContextInitializer.init();
		ApplicationContext context = SpringApplication.run(MainConfig.class, args);
		TelegramBotsApi telegramBotsApi = new TelegramBotsApi();
		Bot customBot = context.getBean(Bot.class);
		try {
			telegramBotsApi.registerBot(customBot);
		} catch (TelegramApiRequestException e) {
			e.printStackTrace();
		}
	}
}
