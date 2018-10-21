package ru.kuzmichev.forwardbot.vk.service;

import com.google.common.cache.LoadingCache;
import com.vk.api.sdk.callback.longpoll.responses.GetLongPollEventsResponse;
import com.vk.api.sdk.callback.longpoll.responses.UserMessage;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.objects.UserAuthResponse;
import com.vk.api.sdk.objects.messages.HistoryAttachment;
import com.vk.api.sdk.objects.messages.LongpollParams;
import com.vk.api.sdk.objects.messages.responses.GetDialogsResponse;
import com.vk.api.sdk.objects.messages.responses.GetHistoryAttachmentsResponse;
import com.vk.api.sdk.objects.photos.Photo;
import com.vk.api.sdk.objects.users.UserXtrCounters;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import ru.kuzmichev.forwardbot.telegram.Bot;
import ru.kuzmichev.forwardbot.utils.Caches;
import ru.kuzmichev.forwardbot.vk.VkClient;
import ru.kuzmichev.forwardbot.vk.callback.CallbackUserApiLongPoolHandler;
import ru.kuzmichev.forwardbot.vk.dto.*;
import ru.kuzmichev.forwardbot.vk.entity.TelegramVkChatMap;
import ru.kuzmichev.forwardbot.vk.entity.VkConfiguration;
import ru.kuzmichev.forwardbot.vk.repository.TelegramVkChatMapRepository;
import ru.kuzmichev.forwardbot.vk.repository.VkConfigurationRepository;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static io.netty.util.internal.StringUtil.EMPTY_STRING;

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
    private static final String VK_CONFIGURATION_CACHE_KEY = "vkCacheKey";
    private static final int MAX_CACHE_SIZE = 10000;
    private static final int CACHE_EXPIRATION_CACHE = 12000;
    private LoadingCache<String, List<VkConfiguration>> vkConfigurationCache;
    private LoadingCache<Long, List<TelegramVkChatMap>> telegramVkChatMapCache;

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
        vkConfigurationCache = Caches.create(MAX_CACHE_SIZE, CACHE_EXPIRATION_CACHE, this::getVkConfigurationCache);
        telegramVkChatMapCache = Caches.create(MAX_CACHE_SIZE, CACHE_EXPIRATION_CACHE, this::getTelegramVkChatMapCache);
        getVkConfigurationFromCache().forEach(config -> {
            boolean result = startReadingMessages(config.getTelegramChatId());
            if (!result) {
                log.error("Error during start reading messages [telegramChatId={}]", config.getTelegramChatId());
                vkConfigurationRepository.delete(config);
                vkConfigurationCache.invalidate(VK_CONFIGURATION_CACHE_KEY);
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
            vkConfigurationCache.invalidate(VK_CONFIGURATION_CACHE_KEY);
            //telegramVkChatMapCache.put(chatId, telegramVkChatMapRepository.findAllByTelegramChatId(chatId));
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
        VkConfiguration config = getVkConfigurationFromCache().stream()
                .filter(c -> c.getTelegramChatId() == chatId)
                .findFirst()
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
        VkConfiguration config = getVkConfigurationFromCache().stream()
                .filter(c -> c.getTelegramChatId() == telegramChatId)
                .findFirst()
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
            telegramVkChatMapCache.invalidate(telegramChatId);
            return true;
        } catch (Exception e) {
            log.error("Error during save filter for [telegramChatId={}], [userId={}]", telegramChatId, userId);
            return false;
        }
    }

    public boolean startReadingMessages(long telegramChatId) {
        VkConfiguration config = getVkConfigurationFromCache().stream()
                .filter(c -> c.getTelegramChatId() == telegramChatId)
                .findFirst()
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
            //CompletableFuture.runAsync(() -> poolMessage(telegramChatId, actor, longpollParams.getTs(), longpollParams));
            CallbackUserApiLongPoolHandler callbackUserApiLongPoolHandler = new CallbackUserApiLongPoolHandler(
                    bot, vkClient, actor, telegramVkChatMapCache, telegramChatId);
            CompletableFuture.runAsync(() -> {
                try {
                    callbackUserApiLongPoolHandler.run();
                } catch (Exception e) {
                    log.error("Fatal error during logPool handling [telegramChatId={}]!", telegramChatId, e);
                }
            });
        } catch (Exception e) {
            log.error("Error", e);
            return false;
        }
        return true;
    }

    public void poolMessage(long telegramChatId, UserActor actor, int ts, LongpollParams params) {
        try {
            CompletableFuture<GetLongPollEventsResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return vkClient
                            .longPoll()
                            .getEvents("https://" + params.getServer(), params.getKey(), ts)
                            //todo: uncomment for enable attachments
                            .unsafeParam("mode", 2)
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
                        List<UserMessage> messages = response.getUserMessages();
                        if (!CollectionUtils.isEmpty(messages)) {
                            Map<Long, String> allowedVkUsers = getTelegramVkChatMapConfigurationFromCahce(telegramChatId).stream()
                                    .collect(Collectors.toMap(TelegramVkChatMap::getVkUserId, TelegramVkChatMap::getVkUserName));
                            //todo: if allowed is empty -> send all messages
                            // filter updates: get only userId from DB
                            messages.stream()
                                    .filter(msg -> allowedVkUsers.containsKey(msg.getUserId()))
                                    .forEach(msg -> CompletableFuture.runAsync(() -> {
                                        bot.sendMsg(new VkMsgToTelegram()
                                            .setChatId(telegramChatId)
                                            .setFrom(allowedVkUsers.get(msg.getUserId()))
                                            .setText(msg.getText())
                                            .setAttachments(msg.getAttachments().stream()
                                                    .map(a -> {
                                                        Attachment dto = new Attachment();
                                                        if ("photo".equalsIgnoreCase(a.getType())) {
                                                            dto.setType(AttachmentType.PHOTO);
                                                            dto.setUrl(getPhotoAttachmentUrl(actor, a));
                                                        } else {
                                                            dto.setUrl(a.getType());
                                                        }
                                                        return dto;
                                                    })
                                                    .collect(Collectors.toList())));
                                    }));
                        }
                        poolMessage(telegramChatId, actor, internalTs, params);
                    }
                });
            } else {
                poolMessage(telegramChatId, actor, ts, params);
            }
        } catch (Exception e){
            poolMessage(telegramChatId, actor, ts, params);
        }
    }

    private String getPhotoAttachmentUrl(UserActor actor, com.vk.api.sdk.callback.longpoll.responses.Attachment attachment) {
        List<HistoryAttachment> photoAttachments = getHistoryPhotoAttachments(actor, attachment.getPeerId());
        HistoryAttachment historyAttachment = photoAttachments.stream()
                .filter(a -> a.getAttachment().getPhoto().getId() == Long.valueOf(attachment.getAttachId()).intValue())
                .findFirst()
                .orElse(null);
        if (historyAttachment != null) {
            Photo photo = historyAttachment.getAttachment().getPhoto();
            if (StringUtils.isNotBlank(photo.getPhoto2560())) {
                return photo.getPhoto2560();
            }
            if (StringUtils.isNotBlank(photo.getPhoto1280())) {
                return photo.getPhoto1280();
            }
            if (StringUtils.isNotBlank(photo.getPhoto807())) {
                return photo.getPhoto807();
            }
            if (StringUtils.isNotBlank(photo.getPhoto604())) {
                return photo.getPhoto604();
            }
            if (StringUtils.isNotBlank(photo.getPhoto130())) {
                return photo.getPhoto130();
            }
            if (StringUtils.isNotBlank(photo.getPhoto75())) {
                return photo.getPhoto75();
            }
        }
        return EMPTY_STRING;
    }

    private List<HistoryAttachment> getHistoryPhotoAttachments(UserActor actor, Long userVkId) {
        try {
            GetHistoryAttachmentsResponse response = vkClient.messages()
                    .getHistoryAttachments(actor, userVkId.intValue())
                    .unsafeParam("media_type", "photo").execute();
            return response.getItems();
        } catch (Exception e) {
            log.error("Exception during getHistoryAttachments: ", e);
            return Collections.EMPTY_LIST;
        }
    }

    private List<VkConfiguration> getVkConfigurationCache(String key) {
        return IterableUtils.toList(vkConfigurationRepository.findAll());
    }

    private List<TelegramVkChatMap> getTelegramVkChatMapCache(Long key) {
        return telegramVkChatMapRepository.findAllByTelegramChatId(key);
    }

    private List<VkConfiguration> getVkConfigurationFromCache() {
        try {
            return vkConfigurationCache.get(VK_CONFIGURATION_CACHE_KEY);
        } catch (ExecutionException e) {
            e.printStackTrace();
            return Collections.EMPTY_LIST;
        }
    }

    private List<TelegramVkChatMap> getTelegramVkChatMapConfigurationFromCahce(long telegramChatId) {
        try {
            return telegramVkChatMapCache.get(telegramChatId);
        } catch (ExecutionException e) {
            e.printStackTrace();
            return Collections.EMPTY_LIST;
        }
    }
}
