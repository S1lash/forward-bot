package ru.kuzmichev.forwardbot.vk.service;

import com.google.common.cache.LoadingCache;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.objects.UserAuthResponse;
import com.vk.api.sdk.objects.messages.responses.GetDialogsResponse;
import com.vk.api.sdk.objects.users.UserXtrCounters;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.IterableUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.kuzmichev.forwardbot.telegram.Bot;
import ru.kuzmichev.forwardbot.utils.Caches;
import ru.kuzmichev.forwardbot.vk.VkClient;
import ru.kuzmichev.forwardbot.vk.callback.CallbackUserApiLongPoolHandler;
import ru.kuzmichev.forwardbot.vk.dto.AddUserToFilterResult;
import ru.kuzmichev.forwardbot.vk.dto.ChatInfo;
import ru.kuzmichev.forwardbot.vk.dto.VkAuthResponse;
import ru.kuzmichev.forwardbot.vk.entity.TelegramVkChatMap;
import ru.kuzmichev.forwardbot.vk.entity.VkConfiguration;
import ru.kuzmichev.forwardbot.vk.repository.LongPollParamsRepository;
import ru.kuzmichev.forwardbot.vk.repository.TelegramVkChatMapRepository;
import ru.kuzmichev.forwardbot.vk.repository.VkConfigurationRepository;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VkService {
    private static final String URL = "https://oauth.vk.com/authorize?";
    private static final String CALLBACK = "callback";
    private static final String CALLBACK_URL = "https://oauth.vk.com/blank.html";
    private static final String CLIENT_ID_KEY = "client_id=";
    private static final String REDIRECT_URI_KEY = "redirect_uri=";
    private static final String VERSION_KEY = "v=";
    private static final String DISPLAY_TYPE = "display=page";
    private static final String RESPONSE_TYPE = "response_type=code";
    private static final String SCOPE = "scope=photos,messages,offline";
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
    private LongPollParamsRepository longPollParamsRepository;
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
    @Value("${admin.telegram.chat.id}")
    private String adminTelegramChatId;
    @Value("${max.exception.count.up.to.notification}")
    private int maxExceptionCount;


    @PostConstruct
    public void init() {
        vkConfigurationCache = Caches.create(MAX_CACHE_SIZE, CACHE_EXPIRATION_CACHE, this::getVkConfigurationCache);
        telegramVkChatMapCache = Caches.create(MAX_CACHE_SIZE, CACHE_EXPIRATION_CACHE, this::getTelegramVkChatMapCache);
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
                .append(VERSION_KEY).append(appApiVersion)
                .append("&")
                .append(CALLBACK).append(CALLBACK_URL);

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

    public AddUserToFilterResult addUserToVkFilter(long telegramChatId, String userId, String screenName) {
        try {
            Long vkUserId = Long.parseLong(userId);
            TelegramVkChatMap alreadyExistChatMap = getTelegramVkChatMapCache(telegramChatId).stream()
                    .filter(m -> m.getVkUserId() == vkUserId)
                    .findFirst()
                    .orElse(null);
            if (alreadyExistChatMap != null) {
                return new AddUserToFilterResult()
                        .setSuccess(false)
                        .setAlreadyExist(true);
            }
            TelegramVkChatMap filter = new TelegramVkChatMap()
                    .setTelegramChatId(telegramChatId)
                    .setVkUserId(vkUserId)
                    .setVkUserName(screenName);
            telegramVkChatMapRepository.save(filter);
            telegramVkChatMapCache.invalidate(telegramChatId);
            return new AddUserToFilterResult()
                    .setSuccess(true);
        } catch (Exception e) {
            log.error("Error during save filter for [telegramChatId={}], [userId={}]", telegramChatId, userId);
            return new AddUserToFilterResult()
                    .setSuccess(false);
        }
    }

    @Scheduled(fixedDelay = 1000 * 20) // 20 seconds
    public void readMessages() {
        getVkConfigurationFromCache()
                .parallelStream()
                .forEach(config -> {
                    try {
                        boolean result = readMessagesPerTChat(config);
                        if (!result) {
                            log.error("Error during start reading messages [telegramChatId={}]", config.getTelegramChatId());
                            vkConfigurationRepository.delete(config);
                            vkConfigurationCache.invalidate(VK_CONFIGURATION_CACHE_KEY);
                            telegramVkChatMapRepository.deleteAllByTelegramChatId(config.getTelegramChatId());
                            telegramVkChatMapCache.invalidate(config.getTelegramChatId());
                        }
                    } catch (Throwable t) {
                        log.error("Unexpected error, during pool messages [telegramChatId={}]", config.getTelegramChatId(), t);
                    }

                });
    }

    private boolean readMessagesPerTChat(VkConfiguration config) {
        UserActor actor = new UserActor(config.getVkUserId().intValue(), config.getVkToken());
        if (actor == null) {
            log.debug("Actor not found for this user [userId={}]", config.getVkUserId());
            return false;
        }
        CallbackUserApiLongPoolHandler callbackUserApiLongPoolHandler = new CallbackUserApiLongPoolHandler(
                bot, vkClient, actor, telegramVkChatMapCache, config, longPollParamsRepository,
                adminTelegramChatId, maxExceptionCount);
        callbackUserApiLongPoolHandler.poolMessage();
        return true;
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
}
