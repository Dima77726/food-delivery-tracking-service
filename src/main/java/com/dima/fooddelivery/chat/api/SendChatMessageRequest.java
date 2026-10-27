package com.dima.fooddelivery.chat.api;

import com.dima.fooddelivery.chat.service.ChatService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * @param text            текст сообщения
 * @param clientMessageId UUID, который клиент создаёт один раз на сообщение и повторяет при
 *                        повторной отправке того же сообщения — ключ идемпотентности
 */
public record SendChatMessageRequest(
        @NotBlank(message = "Сообщение не может быть пустым")
        @Size(max = ChatService.MAX_TEXT_LENGTH, message = "Сообщение не длиннее " + ChatService.MAX_TEXT_LENGTH + " символов")
        String text,

        @NotNull(message = "clientMessageId обязателен")
        UUID clientMessageId
) {
}
