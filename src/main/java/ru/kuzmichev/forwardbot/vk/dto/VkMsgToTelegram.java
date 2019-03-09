package ru.kuzmichev.forwardbot.vk.dto;

import lombok.Data;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static ru.kuzmichev.forwardbot.vk.dto.AttachmentType.UNSUPPORTED;

@Setter
@Accessors(chain = true)
public class VkMsgToTelegram {
    public static final String INBOX = "От:";
    public static final String OUTBOX = "Кому:";
    public static final String UNSUPPORTED_ATTACHMENTS = "Неподдерживаемые вложения:";
    public static final String NEW_LINE = "\n";
    public static final String SPACE = " ";
    public static final String BOLD = "**";
    public static final String ITALIC = "_";
    public static final String BOLD_ITALIC = "***";

    private String from;
    private String text;
    private List<Attachment> attachments = new ArrayList<>();
    private long chatId;
    private boolean outbox;

    public FormattedVkMessage getFormattedMessage() {
        String formattedMessage = getInfo() + getText() + getUnsupportedAttachments();
        return new FormattedVkMessage()
                .setFormattedText(formattedMessage)
                .setOutbox(outbox);
    }

    private String getInfo() {
        StringBuilder sb = new StringBuilder()
                .append(ITALIC).append(outbox ? OUTBOX : INBOX).append(ITALIC).append(SPACE)
                .append(BOLD_ITALIC).append(from).append(BOLD_ITALIC).append(NEW_LINE);
        return sb.toString();
    }

    private String getText() {
        return text + NEW_LINE;
    }

    private String getUnsupportedAttachments() {
        List<Attachment> unsupportedAttachments = attachments.stream()
                .filter(a -> UNSUPPORTED == a.getType())
                .collect(toList());
        String result = "";
        if (!CollectionUtils.isEmpty(unsupportedAttachments)) {
            String unsupportedAttachmentsString = attachments.stream()
                    .filter(a -> UNSUPPORTED == a.getType())
                    .map(Attachment::getUrl)
                    .collect(joining(NEW_LINE + " - ", " - ", ""));
            result = UNSUPPORTED_ATTACHMENTS + NEW_LINE + unsupportedAttachmentsString;
        }
        return result;
    }

    public long getChatId() {
        return this.chatId;
    }

    public List<Attachment> getAttachments() {
        return attachments.stream()
                .filter(a -> UNSUPPORTED != a.getType())
                .collect(toList());
    }

    @Data
    @Accessors(chain = true)
    public class FormattedVkMessage {
        private boolean outbox;
        private String formattedText;
    }
}
