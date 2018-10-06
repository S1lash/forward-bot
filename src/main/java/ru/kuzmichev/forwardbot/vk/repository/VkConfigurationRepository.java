package ru.kuzmichev.forwardbot.vk.repository;


import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import ru.kuzmichev.forwardbot.vk.entity.VkConfiguration;

@Repository
public interface VkConfigurationRepository extends CrudRepository<VkConfiguration, Long> {
}
