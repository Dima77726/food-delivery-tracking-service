package com.dima.fooddelivery.chat.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Сообщение чата заказа.
 *
 * @param messageId       timeuuid: и уникальный идентификатор, и порядок в переписке
 * @param clientMessageId идентификатор, придуманный клиентом: по нему повтор отправки
 *                        (обрыв сети после отправки, двойной клик) не создаёт второго сообщения
 */
public record ChatMessage(
        Long orderId,
        UUID messageId,
        Long senderId,
        ChatRole senderRole,
        String text,
        Instant sentAt,
        UUID clientMessageId
) {
}
