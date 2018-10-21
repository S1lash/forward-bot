package ru.kuzmichev.forwardbot.vk.callback;

import com.google.common.cache.LoadingCache;
import com.vk.api.sdk.callback.longpoll.CallbackApiLongPoll;
import com.vk.api.sdk.callback.longpoll.responses.UserMessage;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.objects.messages.HistoryAttachment;
import com.vk.api.sdk.objects.messages.responses.GetHistoryAttachmentsResponse;
import com.vk.api.sdk.objects.photos.Photo;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import ru.kuzmichev.forwardbot.telegram.Bot;
import ru.kuzmichev.forwardbot.vk.dto.Attachment;
import ru.kuzmichev.forwardbot.vk.dto.AttachmentType;
import ru.kuzmichev.forwardbot.vk.dto.VkMsgToTelegram;
import ru.kuzmichev.forwardbot.vk.entity.TelegramVkChatMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static io.netty.util.internal.StringUtil.EMPTY_STRING;

public class CallbackUserApiLongPoolHandler extends CallbackApiLongPoll {
    private static final Logger LOG = LoggerFactory.getLogger(CallbackUserApiLongPoolHandler.class);
    private final Bot bot;
    private final UserActor userActor;
    private final VkApiClient vkClient;
    private LoadingCache<Long, List<TelegramVkChatMap>> telegramVkChatMapCache;
    private final long telegramChatId;

    public CallbackUserApiLongPoolHandler(Bot bot, VkApiClient client, UserActor actor,
                                          LoadingCache<Long, List<TelegramVkChatMap>> telegramVkChatMapCache, long telegramChatId) {
        super(client, actor);
        this.bot = bot;
        this.userActor = actor;
        this.vkClient = client;
        this.telegramVkChatMapCache = telegramVkChatMapCache;
        this.telegramChatId = telegramChatId;
    }

    @Override
    public void messageNew(UserMessage userMessage) {
        //todo: cache + update
        Map<Long, String> allowedVkUsers = getTelegramVkChatMapConfigurationFromCahce(telegramChatId).stream()
                .collect(Collectors.toMap(TelegramVkChatMap::getVkUserId, TelegramVkChatMap::getVkUserName));
        //todo: if allowed is empty -> send all messages
        // filter updates: get only userId from DB
        if (!allowedVkUsers.containsKey(userMessage.getUserId())) {
            // message from not interesting user =)
            return;
        }
        VkMsgToTelegram telegramMessage = new VkMsgToTelegram()
                .setChatId(telegramChatId)
                .setFrom(allowedVkUsers.get(userMessage.getUserId()))
                .setText(userMessage.getText());
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
