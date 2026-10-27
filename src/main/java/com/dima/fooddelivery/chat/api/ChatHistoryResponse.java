package com.dima.fooddelivery.chat.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @param messages   страница сообщений, новые первыми
 * @param reads      идентификатор пользователя → последнее прочитанное им сообщение
 * @param nextBefore курсор следующей (более старой) страницы или {@code null}, если это последняя
 */
public record ChatHistoryResponse(
        List<ChatMessageResponse> messages,
        Map<Long, UUID> reads,
        UUID nextBefore
) {
}
