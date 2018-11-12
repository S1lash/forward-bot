package ru.kuzmichev.forwardbot.telegram.service;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import org.telegram.telegrambots.api.objects.CallbackQuery;
import org.telegram.telegrambots.api.objects.Message;
import org.telegram.telegrambots.api.objects.Update;
import org.telegram.telegrambots.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.api.objects.replykeyboard.buttons.KeyboardRow;
import ru.kuzmichev.forwardbot.telegram.Bot;
import ru.kuzmichev.forwardbot.vk.dto.AddUserToFilterResult;
import ru.kuzmichev.forwardbot.vk.dto.ChatInfo;
import ru.kuzmichev.forwardbot.vk.dto.VkAuthResponse;
import ru.kuzmichev.forwardbot.vk.service.VkService;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class TelegramService {
    private final String HTTP = "http";
    private final String START = "/start";
    private final String HELP = "/help";
    private final String LOGIN_VK = "/login_vk";
    private final String AUTH = "/auth";
    private final String ADD_USERS = "/addUsers";
    private final String ADD_USER = "/addUser:";
    private final String LOGOUT = "/logout";

    @Resource
    private VkService vkService;

    public SendMessage handleServiceCommand(Update update) {
        Message message = update.getMessage();
        SendMessage sendMessage = new SendMessage()
                .setChatId(message.getChatId());
        if (message.getText().startsWith(HTTP)) {
            VkAuthResponse authResponse = vkService.authorizeByUrl(message.getChatId(), message.getText());
            boolean readingMessagesStarted = vkService.startReadingMessages(message.getChatId());
            if (authResponse.isResult() && readingMessagesStarted) {
                String userName = vkService.getUserName(update.getMessage().getChatId());
                sendMessage.setText(String.format("'%s', поздравляю с успешным подключением!Теперь нужно настроить пересылку " +
                        "сообщений из ВК сюда. Используйте предложенные команды.", userName));
                enableFunctionalVkButtons(sendMessage);
            } else {
                //todo: удалить учетку
                sendMessage.setText("Что то пошло не так..\nПопробуйте сначала..");
                enableInitialButtons(sendMessage);
            }
            return sendMessage;
        }
        switch (message.getText()) {
            case START: {
                sendMessage.setText("Так-с, так-с, так-с.. Кто-то хочет начать пользоваться удобным ботом? Выбирай нужную команду");
                enableInitialButtons(sendMessage);
                break;
            }
            case HELP: {
                sendMessage.setText("Мы пока еще не придумали как вам помочь, попробуйте начать сначала ...");
                enableHelpButtons(sendMessage);
                break;
            }
            case LOGIN_VK: {
                sendMessage.setText("Теперь нужно авторизоваться в ВК. Нужно:\n " +
                        "1. Пройти по ссылке 'Получить доступ';\n" +
                        "2. Авторизоваться в ВК(если не были авторизовани до этого) и разрешить доступ;\n" +
                        "3. Скопировать url (текст из адресной строки), например нажав Поделиться -> Скопировать ссылку;\n" +
                        "4. Прислать содержимое мне.");
                enableLoginVkButtons(sendMessage);
                break;
            }
            case ADD_USERS: {
                sendMessage.setText("Выберете всех пользователей, от которых хотите получать сообщение" +
                        "(здесь указаны только последние 20 ваших диалогов)");
                lastChatsAsButtons(vkService.getLastChats(message.getChatId()), sendMessage);
                break;
            }
            case AUTH: {
                sendMessage.setText("Поздравляю. Вы подключили свой аккаунт {}. Теперь можете настроить его");
                enableFunctionalVkButtons(sendMessage);
                break;
            }
            case LOGOUT: {
                sendMessage.setText("Спасибо, что пользовались мной! Буду вам признателен, " +
                        "если напишете отзыв о моей работе создателю - @s1lash");
                enableInitialButtons(sendMessage);
                break;
            }
            default: {
                sendMessage.setText("Попробуйте воспользоваться предложенными командами");
                enableHelpButtons(sendMessage);
            }
        }
        return sendMessage;
    }

    public SendMessage handleCallback(CallbackQuery callbackQuery) {
        String message = "";
        long telegramChatId = callbackQuery.getFrom().getId().longValue();
        if (callbackQuery.getData().startsWith(ADD_USER)) {
            String[] addUserFilterData = callbackQuery.getData().split(":");
            String userId = addUserFilterData[1];
            String name = addUserFilterData[2];
            AddUserToFilterResult result = vkService.addUserToVkFilter(telegramChatId, userId, name);
            String partOfMessage = result.isSuccess() ? "был" :
                    result.isAlreadyExist() ? "уже" : "не был";
            message = String.format("Пользователь '%s' %s добавлен в ваш фильтр",
                    name,
                    partOfMessage);
        }
        return new SendMessage()
                .setChatId(telegramChatId)
                .setText(message);
    }

    private void enableInitialButtons(SendMessage sendMessage) {
        setButtonsInternal(sendMessage, false, Arrays.asList("/help", "/login_vk"));
    }

    private void enableHelpButtons(SendMessage sendMessage) {
        setButtonsInternal(sendMessage, false, Arrays.asList("/start"));
    }

    private void enableLoginVkButtons(SendMessage sendMessage) {
        setInlineAuthButtons(sendMessage);
        //setButtonsInternal(sendMessage, false, Arrays.asList("/help"));
    }

    private void enableFunctionalVkButtons(SendMessage sendMessage) {
        setButtonsInternal(sendMessage, false, Arrays.asList("/logout", "/addUsers", "doSomething"));
    }

    private void lastChatsAsButtons(List<ChatInfo> chatInfos, SendMessage sendMessage) {
        InlineKeyboardMarkup markupKeyboard = new InlineKeyboardMarkup();
        sendMessage.setReplyMarkup(markupKeyboard);
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();

        chatInfos.forEach(chat -> {
            List<InlineKeyboardButton> rowButtons = new ArrayList<>();
            InlineKeyboardButton button = new InlineKeyboardButton()
                    .setText(chat.getScreenName())
                    .setCallbackData("/addUser:" + chat.getUserId() + ":" + chat.getScreenName());
            rowButtons.add(button);
            buttons.add(rowButtons);
        });

        markupKeyboard.setKeyboard(buttons);
    }

    private void setInlineAuthButtons(SendMessage sendMessage) {
        InlineKeyboardMarkup markupKeyboard = new InlineKeyboardMarkup();
        sendMessage.setReplyMarkup(markupKeyboard);

        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();
        List<InlineKeyboardButton> firstRowButtons = new ArrayList<>();

        String authUrl = vkService.buildAuthorizeUrlRequest();
        InlineKeyboardButton authButton = new InlineKeyboardButton()
                .setText("Авторизоваться")
                .setUrl(authUrl);
        firstRowButtons.add(authButton);

        buttons.add(firstRowButtons);
        markupKeyboard.setKeyboard(buttons);
    }

    private void setButtonsInternal(SendMessage sendMessage, boolean oneTime, List<String> buttons) {
        ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
        sendMessage.setReplyMarkup(replyKeyboardMarkup);
        replyKeyboardMarkup.setSelective(true);
        replyKeyboardMarkup.setResizeKeyboard(true);
        replyKeyboardMarkup.setOneTimeKeyboard(oneTime);

        List<KeyboardRow> keyboardRows = buttons.stream()
                .map(b -> {
                    KeyboardRow keyboardRow = new KeyboardRow();
                    keyboardRow.add(b);
                    return keyboardRow;
                })
                .collect(Collectors.toList());

        replyKeyboardMarkup.setKeyboard(keyboardRows);
    }

}
