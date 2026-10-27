package com.dima.fooddelivery.chat.service;

import com.dima.fooddelivery.chat.domain.ChatMessage;
import com.dima.fooddelivery.chat.live.ChatPublisher;
import com.dima.fooddelivery.chat.persistence.ChatRepository;
import com.dima.fooddelivery.chat.service.ChatParticipants.Participation;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чат заказа между клиентом и курьером (и поддержкой).
 *
 * <p><b>Отправка — по HTTP, доставка — по WebSocket.</b> Сообщение отправляется обычным
 * {@code POST}: у него есть всё, чего нет у кадра STOMP, — код ответа (400, 409, 429), повтор
 * с тем же {@code clientMessageId} без дубликата, ограничение частоты в шлюзе, журнал запросов.
 * Получатели узнают о сообщении из открытого WebSocket — так устроены многие чаты.
 * Эфемерные сигналы («печатает», «прочитано»), которым ответ не нужен, идут кадрами STOMP:
 * их потеря ничего не ломает.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    public static final int MAX_TEXT_LENGTH = 1000;

    private final ChatRepository chatRepository;
    private final ChatParticipants participants;
    private final ChatRateLimiter rateLimiter;
    private final ChatPublisher publisher;

    public ChatMessage send(Long orderId, Long userId, boolean admin, String text, UUID clientMessageId) {
        Participation participation = participants.require(orderId, userId, admin);

        if (participation.closed()) {
            throw new BusinessRuleViolationException("Заказ завершён: писать в его чат уже нельзя");
        }

        long retryAfter = rateLimiter.tryConsume(userId, orderId);
        if (retryAfter > 0) {
            throw new TooManyRequestsException("Слишком много сообщений подряд", retryAfter);
        }

        ChatRepository.Saved saved = chatRepository.save(orderId, userId, participation.role(), text.strip(), clientMessageId);

        if (saved.duplicate()) {
            log.info("Повтор отправки в чат: orderId={}, clientMessageId={}", orderId, clientMessageId);
        } else {
            publisher.message(saved.message());
            log.info("Сообщение в чате: orderId={}, senderRole={}", orderId, participation.role());
        }

        return saved.message();
    }

    public History history(Long orderId, Long userId, boolean admin, UUID before, int limit) {
        participants.require(orderId, userId, admin);

        return new History(chatRepository.findPage(orderId, before, limit), chatRepository.findReads(orderId));
    }

    /** Участник печатает: только живой кадр, без записи. */
    public void typing(Long orderId, Long userId, boolean admin) {
        Participation participation = participants.require(orderId, userId, admin);

        publisher.typing(orderId, userId, participation.role());
    }

    public void markRead(Long orderId, Long userId, boolean admin, UUID upTo) {
        Participation participation = participants.require(orderId, userId, admin);

        chatRepository.markRead(orderId, userId, upTo);
        publisher.read(orderId, userId, participation.role(), upTo);
    }

    /**
     * @param messages страница сообщений, новые первыми
     * @param reads    до какого сообщения дочитал каждый участник
     */
    public record History(List<ChatMessage> messages, Map<Long, UUID> reads) {
    }
}
