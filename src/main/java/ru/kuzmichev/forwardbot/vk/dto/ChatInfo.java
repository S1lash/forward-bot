package ru.kuzmichev.forwardbot.vk.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ChatInfo {
    private Integer userId;
    private String screenName;
}
