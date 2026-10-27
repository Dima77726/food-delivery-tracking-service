package com.dima.fooddelivery.chat.api;

import com.dima.fooddelivery.chat.domain.ChatMessage;
import com.dima.fooddelivery.chat.domain.ChatRole;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageResponse(
        UUID messageId,
        Long orderId,
        Long senderId,
        ChatRole senderRole,
        String text,
        Instant sentAt,
        UUID clientMessageId
) {

    static ChatMessageResponse of(ChatMessage message) {
        return new ChatMessageResponse(message.messageId(), message.orderId(), message.senderId(),
                message.senderRole(), message.text(), message.sentAt(), message.clientMessageId());
    }
}
