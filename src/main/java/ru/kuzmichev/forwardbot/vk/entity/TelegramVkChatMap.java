package ru.kuzmichev.forwardbot.vk.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.persistence.*;

@Data
@Accessors(chain = true)
@Entity(name = "telegram_vk_map")
public class TelegramVkChatMap {

    @Id
    @Column(name = "id", nullable = false)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_telegram_vk_map")
    @SequenceGenerator(name = "seq_telegram_vk_map", sequenceName = "seq_telegram_vk_map")
    private Long id;

    @Column(name = "telegram_chat_id", nullable = false)
    private long telegramChatId;

    @Column(name = "vk_user_id", nullable = false)
    private long vkUserId;

    @Column(name = "vk_user_name", nullable = false)
    private String vkUserName;
}
