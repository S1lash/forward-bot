package ru.kuzmichev.forwardbot.vk.callback;

import com.google.common.cache.LoadingCache;
import com.vk.api.sdk.callback.longpoll.CallbackApiLongPoll;
import com.vk.api.sdk.callback.longpoll.responses.UserMessage;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.objects.messages.HistoryAttachment;
import com.vk.api.sdk.objects.messages.LongpollParams;
import com.vk.api.sdk.objects.messages.responses.GetHistoryAttachmentsResponse;
import com.vk.api.sdk.objects.photos.Photo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import ru.kuzmichev.forwardbot.telegram.Bot;
import ru.kuzmichev.forwardbot.vk.dto.Attachment;
import ru.kuzmichev.forwardbot.vk.dto.AttachmentType;
import ru.kuzmichev.forwardbot.vk.dto.LongPollParamsDto;
import ru.kuzmichev.forwardbot.vk.dto.VkMsgToTelegram;
import ru.kuzmichev.forwardbot.vk.entity.LongPollParamsEntity;
import ru.kuzmichev.forwardbot.vk.entity.TelegramVkChatMap;
import ru.kuzmichev.forwardbot.vk.entity.VkConfiguration;
import ru.kuzmichev.forwardbot.vk.exception.VkPoolException;
import ru.kuzmichev.forwardbot.vk.repository.LongPollParamsRepository;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.netty.util.internal.StringUtil.EMPTY_STRING;

@Slf4j
public class CallbackUserApiLongPoolHandler extends CallbackApiLongPoll {
    private static final Logger LOG = LoggerFactory.getLogger(CallbackUserApiLongPoolHandler.class);
    private static final int OUTBOX_FLAG = 2;
    private final Bot bot;
    private final UserActor userActor;
    private final VkApiClient vkClient;
    private LoadingCache<Long, List<TelegramVkChatMap>> telegramVkChatMapCache;
    private final VkConfiguration vkConfig;
    private final LongPollParamsRepository longPollParamsRepository;
    private volatile AtomicInteger poolParamsExceptionCount = new AtomicInteger(0);
    private volatile AtomicInteger poolMessagesExceptionCount = new AtomicInteger(0);
    private final String adminTelegramChatId;
    private final int maxExceptionCount;

    public CallbackUserApiLongPoolHandler(Bot bot, VkApiClient client, UserActor actor,
                                          LoadingCache<Long, List<TelegramVkChatMap>> telegramVkChatMapCache,
                                          VkConfiguration vkConfig, LongPollParamsRepository longPollParamsRepository,
                                          String adminTelegramChatId, int maxExceptionCount) {
        super(client, actor);
        this.bot = bot;
        this.userActor = actor;
        this.vkClient = client;
        this.telegramVkChatMapCache = telegramVkChatMapCache;
        this.vkConfig = vkConfig;
        this.longPollParamsRepository = longPollParamsRepository;
        this.adminTelegramChatId = adminTelegramChatId;
        this.maxExceptionCount = maxExceptionCount;
    }

    public void poolMessage() {
        LongPollParamsDto longPollParams = null;
        try {
            longPollParams = getLongPoolParamsInternal();
        } catch (VkPoolException e) {
            poolParamsExceptionCount.getAndIncrement();
            handleException(e, poolParamsExceptionCount);
            return;
        }

        if (longPollParams == null) {
            return;
        }

        Integer newTs;
        LongPollParamsEntity entity = longPollParamsRepository.findByTelegramChatId(vkConfig.getTelegramChatId());
        try {
            newTs = doUserPool(longPollParams.getServer(), longPollParams.getKey(), longPollParams.getTs());
        } catch (Throwable t) {
            log.error("Error during polling messages [telegramChatId={}]", vkConfig.getTelegramChatId(), t);
            longPollParamsRepository.delete(entity);
            poolMessagesExceptionCount.getAndIncrement();
            VkPoolException ex = new VkPoolException()
                    .setError(t)
                    .setVkUserId(vkConfig.getVkUserId());
            handleException(ex, poolParamsExceptionCount);
            return;
        }
        entity.setTs(newTs);
        longPollParamsRepository.save(entity);
    }

    private void handleException(VkPoolException ex, AtomicInteger count) {
        if (count.get() >= maxExceptionCount) {
            count.set(0);
            SendMessage sendMessage = new SendMessage()
                    .setChatId(adminTelegramChatId)
                    .setText(ex.buildErrorMessage());
            bot.sendMsg(sendMessage);
        }
    }

    private LongPollParamsDto getLongPoolParamsInternal() throws VkPoolException {
        LongPollParamsEntity entity = longPollParamsRepository.findByTelegramChatId(vkConfig.getTelegramChatId());
        if (entity == null) {
            return tryGetRemoteLongPollParams();
        }
        if (isExpired(entity) || entity.getTs() == null || entity.getTs() <= 1) {
            longPollParamsRepository.delete(entity);
            return tryGetRemoteLongPollParams();
        }
        return new LongPollParamsDto()
                .setKey(entity.getKey())
                .setServer(entity.getServer())
                .setTs(entity.getTs())
                .setTelegramChatId(entity.getTelegramChatId());
    }

    private boolean isExpired(LongPollParamsEntity entity) {
        Date oneHourAgo = new Date(System.currentTimeMillis() - 1000 * 60 * 58); // 58 minutes
        if (entity.getUpdatedDate().before(oneHourAgo)) {
            return true;
        }
        return false;
    }

    private LongPollParamsDto tryGetRemoteLongPollParams() throws VkPoolException {
        LongpollParams params;
        try {
            params = getUserLongPoollParams();
        } catch (Throwable t) {
            log.error("Error during getting longPollParams [telegramChatId={}]", vkConfig.getTelegramChatId());
            log.error("Error message: {}]", t.getMessage());
            log.error("Error cause message: {}]", t.getCause() != null ? t.getCause().getMessage() : null);

            throw new VkPoolException()
                    .setError(t)
                    .setVkUserId(vkConfig.getVkUserId());
        }

        LongPollParamsEntity entity = new LongPollParamsEntity()
                .setKey(params.getKey())
                .setServer("https://" + params.getServer())
                .setTs(params.getTs())
                .setTelegramChatId(vkConfig.getTelegramChatId())
                .setUpdatedDate(new Date());
        longPollParamsRepository.save(entity);

        return new LongPollParamsDto()
                .setKey(entity.getKey())
                .setServer(entity.getServer())
                .setTs(entity.getTs())
                .setTelegramChatId(vkConfig.getTelegramChatId());
    }

    @Override
    public void messageNew(UserMessage userMessage) {
        //todo: cache + update
        Map<Long, String> allowedVkUsers = getTelegramVkChatMapConfigurationFromCahce(vkConfig.getTelegramChatId()).stream()
                .collect(Collectors.toMap(TelegramVkChatMap::getVkUserId, TelegramVkChatMap::getVkUserName));

        // filter updates: get only userId from DB
        if (!CollectionUtils.isEmpty(allowedVkUsers) && !allowedVkUsers.containsKey(userMessage.getUserId())) {
            // message from not interesting user =)
            return;
        }
        boolean outbox = false;
        try {
            int flag = Integer.valueOf(userMessage.getFlag());
            outbox = (flag & OUTBOX_FLAG) != 0;
        } catch (Exception e) {
            // do nothing
        }
        VkMsgToTelegram telegramMessage = new VkMsgToTelegram()
                .setChatId(vkConfig.getTelegramChatId())
                .setFrom(allowedVkUsers.get(userMessage.getUserId()))
                .setText(userMessage.getText())
                .setOutbox(outbox);
        if (!CollectionUtils.isEmpty(userMessage.getAttachments())) {
            List<Attachment> attachments = new ArrayList<>();
            for (com.vk.api.sdk.callback.longpoll.responses.Attachment attachment : userMessage.getAttachments()) {
                Attachment dto = new Attachment();
                if ("photo".equalsIgnoreCase(attachment.getType())) {
                    dto.setType(AttachmentType.PHOTO);
                    dto.setUrl(getPhotoAttachmentUrl(userActor, attachment));
                } else {
                    dto.setUrl(attachment.getType());
                }
                attachments.add(dto);
            }
            telegramMessage.setAttachments(attachments);
        }
        bot.sendMsg(telegramMessage);
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
            LOG.error("Exception during getHistoryAttachments: ", e);
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
