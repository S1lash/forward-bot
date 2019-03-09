package ru.kuzmichev.forwardbot.vk.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import java.util.Date;

@Data
@Accessors(chain = true)
@Entity(name = "long_poll_params")
public class LongPollParamsEntity {

    @Id
    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;

    @Column(name = "ts")
    private Integer ts;

    @Column(name = "server", nullable = false)
    private String server;

    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "updated_date", nullable = false)
    private Date updatedDate;
}
