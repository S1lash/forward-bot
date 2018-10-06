package ru.kuzmichev.forwardbot.vk.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

@Data
@Accessors(chain = true)
@Entity(name = "vk_configuration")
public class VkConfiguration {

    @Id
    @Column(name = "telegram_chat_id", nullable = false)
    private long telegramChatId;

    @Column(name = "token", nullable = false)
    private String vkToken;

    @Column(name = "user_id", nullable = false)
    private Long vkUserId; //Long-объект для удобного парсинга в int
}
