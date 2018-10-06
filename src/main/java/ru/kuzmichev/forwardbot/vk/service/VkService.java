package ru.kuzmichev.forwardbot.vk.service;

import com.vk.api.sdk.callback.longpoll.responses.GetLongPollEventsResponse;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.objects.UserAuthResponse;
import com.vk.api.sdk.objects.messages.LongpollParams;
import com.vk.api.sdk.objects.messages.responses.GetDialogsResponse;
import com.vk.api.sdk.objects.users.UserXtrCounters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.telegram.telegrambots.api.methods.send.SendMessage;
import ru.kuzmichev.forwardbot.telegram.Bot;
import ru.kuzmichev.forwardbot.vk.VkClient;
import ru.kuzmichev.forwardbot.vk.dto.ChatInfo;
import ru.kuzmichev.forwardbot.vk.dto.VkAuthResponse;
import ru.kuzmichev.forwardbot.vk.entity.TelegramVkChatMap;
import ru.kuzmichev.forwardbot.vk.entity.VkConfiguration;
import ru.kuzmichev.forwardbot.vk.repository.VkConfigurationRepository;
import ru.kuzmichev.forwardbot.vk.repository.TelegramVkChatMapRepository;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VkService {
    private static final String URL = "https://oauth.vk.com/authorize?";
    private static final String CLIENT_ID_KEY = "client_id=";
    private static final String REDIRECT_URI_KEY = "redirect_uri=";
    private static final String VERSION_KEY = "v=";
    private static final String DISPLAY_TYPE = "display=page";
    private static final String RESPONSE_TYPE = "response_type=code";
    private static final String SCOPE = "scope=messages,offline";
    private static final String NEW_MESSAGE = "4";

    @Resource
    private VkClient vkClient;
    @Resource
    private VkConfigurationRepository vkConfigurationRepository;
    @Resource
    private TelegramVkChatMapRepository telegramVkChatMapRepository;
    @Resource
    private Bot bot;

    @Value("${vk.app.api.id}")
    private Long appApiId;
    @Value("${vk.app.api.secret.key}")
    private String appApiSecretKey;
    @Value("${vk.app.api.version}")
    private String appApiVersion;
    @Value("${vk.app.api.redirect.uri}")
    private String appApiRedirectUri;


    @PostConstruct
    public void init() {
        vkConfigurationRepository.findAll().forEach(config -> {
            boolean result = startReadingMessages(config.getTelegramChatId());
            if (!result) {
                log.error("Error during start reading messages [telegramChatId={}]", config.getTelegramChatId());
                vkConfigurationRepository.delete(config);
            }
        });
    }

    public String buildAuthorizeUrlRequest() {
        StringBuilder requestUrl = new StringBuilder(URL);
        requestUrl
                .append(CLIENT_ID_KEY).append(appApiId)
                .append("&")
                .append(REDIRECT_URI_KEY).append(appApiRedirectUri)
                .append("&")
                .append(DISPLAY_TYPE)
                .append("&")
                .append(SCOPE)
                .append("&")
                .append(RESPONSE_TYPE)
                .append("&")
                .append(VERSION_KEY).append(appApiVersion);

        return requestUrl.toString();
    }

    public VkAuthResponse authorizeByUrl(long chatId, String httpResponse) {
        VkAuthResponse response = new VkAuthResponse();
        try {
            URI uri = new URI(httpResponse);
            String code = uri.getFragment().replace("code=", "");
            UserAuthResponse authResponse = vkClient
                    .oauth()
                    .userAuthorizationCodeFlow(appApiId.intValue(), appApiSecretKey, appApiRedirectUri, code)
                    .execute();
            VkConfiguration configuration = new VkConfiguration()
                    .setTelegramChatId(chatId)
                    .setVkToken(authResponse.getAccessToken())
                    .setVkUserId(authResponse.getUserId().longValue());
            vkConfigurationRepository.save(configuration);
            response
                    .setUserName(getUserNameInternal(authResponse.getUserId(), authResponse.getAccessToken()))
                    .setResult(true);
        } catch (Exception e) {
            log.error("Error during vk authorization [url={}]", httpResponse, e);
        }
        return response;
    }

    @Nullable
    public String getUserName(long chatId) {
        VkConfiguration config = vkConfigurationRepository
                .findById(chatId)
                .orElse(null);
        if (config == null) {
            log.debug("Vk configuration not found for this chat [id={}]", chatId);
            return null;
        }
        return getUserNameInternal(config.getVkUserId().intValue(), config.getVkToken());
    }

    @Nullable
    private String getUserNameInternal(int userId, String token) {
        UserActor actor = new UserActor(userId, token);
        if (actor == null) {
            log.debug("Actor not found for this user [userId={}]", userId);
            return null;
        }
        try {
            UserXtrCounters counters = vkClient.users().get(actor).execute().get(0);
            return counters.getFirstName() + " " + counters.getLastName();
        } catch (Exception e) {
            log.debug("Error during getting first/last VK name of user [userId={}]", userId);
            return null;
        }
    }

    public List<ChatInfo> getLastChats(long telegramChatId) {
        VkConfiguration config = vkConfigurationRepository
                .findById(telegramChatId)
                .orElse(null);
        if (config == null) {
            log.debug("Vk configuration not found for this chat [id={}]", telegramChatId);
            return Collections.EMPTY_LIST;
        }
        UserActor actor = new UserActor(config.getVkUserId().intValue(), config.getVkToken());
        if (actor == null) {
            log.debug("Actor not found for this user [userId={}]", config.getVkUserId());
            return Collections.EMPTY_LIST;
        }
        List<ChatInfo> chatInfos = new ArrayList<>();
        try {
            GetDialogsResponse dialogsResponse = vkClient.messages().getDialogs(actor).execute();
            List<String> userIds = dialogsResponse.getItems().stream()
                    .map(d -> String.valueOf(d.getMessage().getUserId()))
                    .collect(Collectors.toList());
            chatInfos = vkClient.users().get(actor).userIds(userIds).execute().stream()
                    .map(u -> new ChatInfo()
                            .setScreenName(u.getFirstName() + " " + u.getLastName())
                            .setUserId(u.getId()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error during build list of last 20 chats [telegramChatId={}]", telegramChatId, e);
        }
        return chatInfos;
    }

    public boolean addUserToVkFilter(long telegramChatId, String userId, String screenName) {
        try {
            TelegramVkChatMap filter = new TelegramVkChatMap()
                    .setTelegramChatId(telegramChatId)
                    .setVkUserId(Long.parseLong(userId))
                    .setVkUserName(screenName);
            telegramVkChatMapRepository.save(filter);
            return true;
        } catch (Exception e) {
            log.error("Error during save filter for [telegramChatId={}], [userId={}]", telegramChatId, userId);
            return false;
        }
    }

    public boolean startReadingMessages(long telegramChatId) {
        VkConfiguration config = vkConfigurationRepository
                .findById(telegramChatId)
                .orElse(null);
        if (config == null) {
            log.debug("Vk configuration not found for this chat [id={}]", telegramChatId);
            return false;
        }
        UserActor actor = new UserActor(config.getVkUserId().intValue(), config.getVkToken());
        if (actor == null) {
            log.debug("Actor not found for this user [userId={}]", config.getVkUserId());
            return false;
        }
        try {
            LongpollParams longpollParams = vkClient.messages().getLongPollServer(actor).execute();
            CompletableFuture.runAsync(() -> poolMessage(telegramChatId, longpollParams.getTs(), longpollParams));
        } catch (Exception e) {
            log.error("Error", e);
            return false;
        }
        return true;
    }

    public void poolMessage(long telegramChatId, int ts, LongpollParams params) {
        try {
            CompletableFuture<GetLongPollEventsResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return vkClient
                            .longPoll()
                            .getEvents("https://" + params.getServer(), params.getKey(), ts)
                            .waitTime(25)
                            .execute();
                } catch (Exception e) {
                    log.error("Error", e);
                    return null;
                }
            });
            if (future != null) {
                future.thenAccept(response -> {
                    int internalTs = response.getTs();
                    if (!CollectionUtils.isEmpty(response.getUpdates())) {
                        // filter updates: get only new messages
                        List<String[]> updates =response.getUpdates().stream()
                                .filter(upd -> NEW_MESSAGE.equals(upd[0]))
                                .collect(Collectors.toList());
                        if (!CollectionUtils.isEmpty(updates)) {
                            Set<Long> allowedVkUserIds = telegramVkChatMapRepository
                                    .findAllByTelegramChatId(telegramChatId).stream()
                                    .map(TelegramVkChatMap::getVkUserId)
                                    .collect(Collectors.toSet());
                            //todo: if allowed is empty -> send all messages
                            // filter updates: get only userId from DB
                            List<SendMessage> messages = updates.stream()
                                    .filter(upd -> allowedVkUserIds.contains(Long.parseLong(upd[3])))
                                    .map(upd -> new SendMessage()
                                            .setChatId(telegramChatId)
                                            .setText(upd[6]))
                                    .collect(Collectors.toList());
                            messages.forEach(msg -> bot.sendMsg(msg));
                        }
                        poolMessage(telegramChatId, internalTs, params);
                    }
                });
            } else {
                poolMessage(telegramChatId, ts, params);
            }
        } catch (Exception e){
            poolMessage(telegramChatId, ts, params);
        }
    }


}
