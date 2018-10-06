package ru.kuzmichev.forwardbot.vk;

import com.vk.api.sdk.client.TransportClient;
import com.vk.api.sdk.client.VkApiClient;

public class VkClient extends VkApiClient {

    public VkClient(TransportClient transportClient) {
        super(transportClient);
    }
}
