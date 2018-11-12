package ru.kuzmichev.forwardbot.vk.dto;

import lombok.Data;
import lombok.experimental.Accessors;


@Data
@Accessors(chain = true)
public class AddUserToFilterResult {
    boolean success;
    boolean alreadyExist;
}
