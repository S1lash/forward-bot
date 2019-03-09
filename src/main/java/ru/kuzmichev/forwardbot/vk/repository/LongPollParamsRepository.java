package ru.kuzmichev.forwardbot.vk.repository;

import org.springframework.data.repository.CrudRepository;
import ru.kuzmichev.forwardbot.vk.entity.LongPollParamsEntity;

public interface LongPollParamsRepository extends CrudRepository<LongPollParamsEntity, Long> {
    LongPollParamsEntity findByTelegramChatId(long telegramChatId);
}
