package ru.kuzmichev.forwardbot.vk.repository;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import ru.kuzmichev.forwardbot.vk.entity.TelegramVkChatMap;

import java.util.List;

@Repository
public interface TelegramVkChatMapRepository extends CrudRepository<TelegramVkChatMap, Long> {
    List<TelegramVkChatMap> findAllByTelegramChatId(long telegramChatId);
    TelegramVkChatMap findDistinctFirstByVkUserId(long userId);
    void deleteAllByTelegramChatId(long telegramChatId);
}
