package ru.kuzmichev.forwardbot.vk.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Attachment {
    private AttachmentType type = AttachmentType.UNSUPPORTED;
    private String url;
}
