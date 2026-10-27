package com.dima.fooddelivery.chat.live;

import com.dima.fooddelivery.chat.domain.ChatMessage;
import com.dima.fooddelivery.chat.domain.ChatRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Рассылка кадров чата подписчикам заказа — через тот же ретранслятор в RabbitMQ, что и трек:
 * кадр дойдёт до участника, к какому бы экземпляру сервиса он ни был подключён.
 *
 * <p>Как и у трека, сбой рассылки не отменяет запись: сообщение уже в Cassandra, клиент
 * увидит его, перечитав историю.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPublisher {

    public static final String TOPIC_PREFIX = "/topic/order.";
    public static final String TOPIC_SUFFIX = ".chat";

    private final SimpMessagingTemplate messagingTemplate;

    public static String destination(Long orderId) {
        return TOPIC_PREFIX + orderId + TOPIC_SUFFIX;
    }

    public void message(ChatMessage message) {
        send(message.orderId(), ChatEvent.message(message));
    }

    public void typing(Long orderId, Long senderId, ChatRole role) {
        send(orderId, ChatEvent.typing(orderId, senderId, role));
    }

    public void read(Long orderId, Long senderId, ChatRole role, UUID upTo) {
        send(orderId, ChatEvent.read(orderId, senderId, role, upTo));
    }

    private void send(Long orderId, ChatEvent event) {
        try {
            messagingTemplate.convertAndSend(destination(orderId), event);
        } catch (MessagingException exception) {
            log.warn("Не удалось разослать кадр чата: orderId={}, type={}, reason={}",
                    orderId, event.type(), exception.getMessage());
        }
    }
}
