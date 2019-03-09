package ru.kuzmichev.forwardbot.telegram;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.updateshandlers.SentCallback;
import ru.kuzmichev.forwardbot.telegram.service.TelegramService;
import ru.kuzmichev.forwardbot.vk.dto.VkMsgToTelegram;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

import static ru.kuzmichev.forwardbot.vk.dto.AttachmentType.PHOTO;

@Slf4j
@Service
public class Bot extends TelegramLongPollingBot {

    @Resource
    private TelegramService telegramService;

    @Value("${telegram.bot.api.token}")
    private String botApiToken;
    @Value("${telegram.bot.name}")
    private String botName;

    @Override
    public void onUpdateReceived(Update update) {
        log.debug("Update from user : [{}]", update);
        SendMessage sendMessage;
        if (update.hasCallbackQuery()) {
            CallbackQuery callbackQuery = update.getCallbackQuery();
            sendMessage = telegramService.handleCallback(callbackQuery);
            AnswerCallbackQuery answerCallbackQuery = new AnswerCallbackQuery();
            answerCallbackQuery.setCallbackQueryId(callbackQuery.getId());
            answerCallbackQuery.setShowAlert(true);
            answerCallbackQuery.setText(sendMessage.getText());
            sendApiMethodAsync(answerCallbackQuery, new CustomAnswerCallbackQuerySentCallback());
            return;
        } else {
            sendMessage = telegramService.handleServiceCommand(update);
        }
        sendMsg(sendMessage);
    }

    @Override
    public void onUpdatesReceived(List<Update> updates) {
        updates.parallelStream()
                .forEach(upd -> onUpdateReceived(upd));
    }

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return botApiToken;
    }

    public void sendMsg(SendMessage sendMessage) {
        if (sendMessage == null || StringUtils.isBlank(sendMessage.getChatId())) {
            return;
        }

        sendMessage.enableMarkdown(true);
        try {
            sendApiMethod(sendMessage);
        } catch (TelegramApiException e) {
            log.debug("Exception: ", e.toString());
        }
    }

    public void sendPhotos(long chatId, List<InputMedia> photos) {
        photos = photos.stream()
                .filter(p -> StringUtils.isNotBlank(p.getMedia()))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(photos)) {
            return;
        }
        try {
            SendMediaGroup sendMediaGroup = new SendMediaGroup()
                    .setChatId(chatId);
            //todo: caption to first
            sendMediaGroup.setMedia(photos);
            this.execute(sendMediaGroup);
        } catch (Throwable e) {
            log.debug("Exception: ", e.toString());
        }
    }

    public void sendMsg(VkMsgToTelegram message) {
        if (message == null) {
            return;
        }
        VkMsgToTelegram.FormattedVkMessage formattedVkMessage = message.getFormattedMessage();
        SendMessage sendMessage = new SendMessage()
                .setText(formattedVkMessage.getFormattedText())
                .setChatId(message.getChatId());
        if (formattedVkMessage.isOutbox()) {
            sendMessage.disableNotification();
        }

        List<InputMedia> mediaPhotos = message.getAttachments().stream()
                    .filter(a -> PHOTO == a.getType())
                    .map(a -> new InputMediaPhoto()
                            .setMedia(a.getUrl()))
                    .collect(Collectors.toList());

        sendMsg(sendMessage);
        sendPhotos(message.getChatId(), mediaPhotos);
    }

    private static class CustomAnswerCallbackQuerySentCallback implements SentCallback<Boolean> {

        public CustomAnswerCallbackQuerySentCallback() {

        }

        @Override
        public void onResult(BotApiMethod<Boolean> method, Boolean response) {

        }

        @Override
        public void onError(BotApiMethod<Boolean> method, TelegramApiRequestException apiException) {

        }

        @Override
        public void onException(BotApiMethod<Boolean> method, Exception exception) {

        }
    }
}
