package com.dima.fooddelivery.tracking.mqtt;

import com.dima.fooddelivery.tracking.service.TrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Точки курьеров по MQTT.
 *
 * <pre>
 * телефон курьера ──MQTT──▶ RabbitMQ (плагин rabbitmq_mqtt, порт 1883; из браузера — MQTT поверх WebSocket, 15675)
 *   PUBLISH couriers/42/deliveries/7/position   {"latitude":55.75,"longitude":37.61,"speedKmh":18}
 *                     │  плагин переводит топик в ключ маршрутизации: "/" → "."
 *                     ▼
 *   amq.topic, ключ couriers.42.deliveries.7.position ──▶ очередь tracking.courier-positions ──▶ здесь
 * </pre>
 *
 * <p><b>Зачем MQTT, если есть REST.</b> Телефон в машине теряет связь в каждом тоннеле, а батарея
 * дорогая. MQTT держит одно лёгкое соединение (заголовок пакета — 2 байта против сотен у HTTP),
 * переживает переподключения (сессия и QoS 1 — «доставить хотя бы раз»), а будущее «последнее
 * слово» (last will) сообщит о пропаже курьера без опроса. Это протокол IoT-устройств, и телефон
 * курьера здесь — именно устройство.
 *
 * <p><b>Брокер уже есть.</b> RabbitMQ говорит на MQTT плагином, и сообщение становится обычным
 * сообщением AMQP: сервису не нужен ни отдельный брокер (Mosquitto, EMQX), ни MQTT-клиент.
 *
 * <p><b>Кто может публиковать.</b> Курьер подключается к брокеру со своим access-токеном вместо
 * пароля; брокер спрашивает об этом {@link RabbitHttpAuthController}, и тот разрешает писать
 * только в топики со своим идентификатором. Здесь права проверяются ещё раз — что доставка
 * действительно его и уже в пути: та же {@link TrackingService#reportPosition}, что и у REST.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.tracking.mqtt.enabled", havingValue = "true")
public class CourierPositionMqtt {

    public static final String QUEUE = "tracking.courier-positions";
    public static final String ROUTING_PATTERN = "couriers.*.deliveries.*.position";

    static final Pattern ROUTING_KEY = Pattern.compile("^couriers\\.(\\d+)\\.deliveries\\.(\\d+)\\.position$");

    private final TrackingService trackingService;
    private final ObjectMapper objectMapper;

    @Bean
    Queue courierPositionsQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    /**
     * Привязка к {@code amq.topic} без объявления самого обмена: обмены {@code amq.*} брокер создаёт
     * сам и объявлять их заново не даёт (ACCESS_REFUSED).
     */
    @Bean
    Binding courierPositionsBinding() {
        return new Binding(QUEUE, Binding.DestinationType.QUEUE, "amq.topic", ROUTING_PATTERN, null);
    }

    @RabbitListener(queues = QUEUE)
    public void onPosition(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        Matcher matcher = routingKey == null ? null : ROUTING_KEY.matcher(routingKey);
        if (matcher == null || !matcher.matches()) {
            throw new AmqpRejectAndDontRequeueException("Неизвестный топик: " + routingKey);
        }

        long courierId = Long.parseLong(matcher.group(1));
        long deliveryId = Long.parseLong(matcher.group(2));

        JsonNode body;
        try {
            body = objectMapper.readTree(message.getBody());
        } catch (RuntimeException exception) {
            throw new AmqpRejectAndDontRequeueException("Точка не разбирается как JSON", exception);
        }
        double latitude = body.path("latitude").asDouble(Double.NaN);
        double longitude = body.path("longitude").asDouble(Double.NaN);
        if (Double.isNaN(latitude) || Double.isNaN(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new AmqpRejectAndDontRequeueException("Координаты вне диапазона");
        }
        Double speed = body.hasNonNull("speedKmh") ? body.path("speedKmh").asDouble() : null;

        try {
            trackingService.reportPosition(courierId, deliveryId, latitude, longitude, speed);
        } catch (RuntimeException exception) {
            // Чужая доставка, доставка не в пути, заказа нет — повтор не поможет.
            log.warn("Точка по MQTT отклонена: courierId={}, deliveryId={}, reason={}",
                    courierId, deliveryId, exception.getMessage());
            throw new AmqpRejectAndDontRequeueException(exception.getMessage(), exception);
        }
    }
}
