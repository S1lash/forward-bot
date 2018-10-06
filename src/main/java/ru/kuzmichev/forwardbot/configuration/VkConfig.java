package ru.kuzmichev.forwardbot.configuration;

import com.vk.api.sdk.client.TransportClient;
import com.vk.api.sdk.httpclient.HttpTransportClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.kuzmichev.forwardbot.vk.VkClient;

@Configuration
public class VkConfig {

    @Bean
    public VkClient vkClient() {
        TransportClient transportClient = HttpTransportClient.getInstance();
        return new VkClient(transportClient);
    }
}
