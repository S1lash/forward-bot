package ru.kuzmichev.forwardbot.vk.dto;


import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LongPollParamsDto {
    private Long telegramChatId;
    private Integer ts;
    private String server;
    private String key;
}
