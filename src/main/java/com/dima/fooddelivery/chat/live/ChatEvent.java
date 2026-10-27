package com.dima.fooddelivery.chat.live;

import com.dima.fooddelivery.chat.domain.ChatMessage;
import com.dima.fooddelivery.chat.domain.ChatRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Кадр живого чата в {@code /topic/order.{orderId}.chat}. Один адрес, три вида кадров —
 * клиенту достаточно одной подписки:
 * <ul>
 *     <li>{@code MESSAGE} — новое сообщение (всё, кроме {@code upTo});</li>
 *     <li>{@code TYPING} — участник печатает: только отправитель, ничего не хранится;</li>
 *     <li>{@code READ} — участник дочитал до сообщения {@code upTo}.</li>
 * </ul>
 */
public record ChatEvent(
        Type type,
        Long orderId,
        UUID messageId,
        Long senderId,
        ChatRole senderRole,
        String text,
        Instant sentAt,
        UUID clientMessageId,
        UUID upTo
) {

    public enum Type { MESSAGE, TYPING, READ }

    static ChatEvent message(ChatMessage message) {
        return new ChatEvent(Type.MESSAGE, message.orderId(), message.messageId(), message.senderId(),
                message.senderRole(), message.text(), message.sentAt(), message.clientMessageId(), null);
    }

    static ChatEvent typing(Long orderId, Long senderId, ChatRole role) {
        return new ChatEvent(Type.TYPING, orderId, null, senderId, role, null, Instant.now(), null, null);
    }

    static ChatEvent read(Long orderId, Long senderId, ChatRole role, UUID upTo) {
        return new ChatEvent(Type.READ, orderId, null, senderId, role, null, Instant.now(), null, upTo);
    }
}
