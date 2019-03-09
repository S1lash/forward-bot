package ru.kuzmichev.forwardbot.vk.exception;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class VkPoolException extends Exception {
    private Throwable error;
    private Long vkUserId;

    public String buildErrorMessage() {
        StringBuilder sb = new StringBuilder("VkPoolException {\n");
        if (error != null) {
            sb.append("   errorMessage = ").append(error.getMessage()).append(",\n");
            Throwable cause = error.getCause();
            if (cause != null) {
                sb.append("   causeMessage = ").append(cause.getMessage()).append(",\n");
                Throwable cause2 = cause.getCause();
                if (cause2 != null) {
                    sb.append("   cause2Message = ").append(cause2.getMessage()).append(",\n");
                }
            }
        }
        sb.append("   vkUserId = ").append(vkUserId).append("\n}");
        return sb.toString();
    }
}
