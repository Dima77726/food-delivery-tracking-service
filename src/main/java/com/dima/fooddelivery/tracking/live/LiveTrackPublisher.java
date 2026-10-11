package com.dima.fooddelivery.tracking.live;

import com.dima.fooddelivery.tracking.api.TrackResponseMapper;
import com.dima.fooddelivery.tracking.domain.CourierPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Рассылает новую точку курьера подписчикам заказа.
 *
 * <p>Адрес — {@code /topic/order.{orderId}.track}. С ретранслятором в RabbitMQ это обменник
 * {@code amq.topic} с ключом {@code order.42.track}: брокер сам доставит точку на каждое
 * WebSocket-соединение, подписанное на этот заказ, к какому бы экземпляру сервиса оно
 * ни было подключено. Встроенный в Spring брокер держал бы подписки в памяти одного процесса,
 * и клиент, подключённый ко второму экземпляру, не увидел бы точек, пришедших в первый.
 *
 * <p>Неудачная рассылка не ломает запись точки: трек уже в Cassandra, и клиент получит его
 * при следующем чтении. Живой канал — ускорение, а не источник правды.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveTrackPublisher {

    public static final String TOPIC_PREFIX = "/topic/order.";
    public static final String TOPIC_SUFFIX = ".track";

    private final SimpMessagingTemplate messagingTemplate;

    public static String destination(Long orderId) {
        return TOPIC_PREFIX + orderId + TOPIC_SUFFIX;
    }

    public void publish(Long orderId, CourierPosition position) {
        try {
            messagingTemplate.convertAndSend(destination(orderId), TrackResponseMapper.toResponse(position));
        } catch (MessagingException exception) {
            log.warn("Не удалось разослать точку подписчикам: orderId={}, reason={}", orderId, exception.getMessage());
        }
    }
}
