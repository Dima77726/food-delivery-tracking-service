package com.dima.fooddelivery.tracking.mqtt;

import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.tracking.service.TrackingService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Точки курьеров по MQTT и HTTP-бэкенд аутентификации брокера.
 *
 * <p>Публикация идёт прямо в {@code amq.topic} с тем ключом, в который плагин rabbitmq_mqtt
 * переводит MQTT-топик ({@code couriers/42/...} → {@code couriers.42....}): дальше плагина путь
 * сообщения тот же. Сам MQTT-клиент и плагин проверяются вживую на стенде.
 */
class MqttGpsIT extends AbstractIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TrackingService trackingService;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void shouldStorePositionPublishedToCourierTopic() throws Exception {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();
        Long orderId = testData.insertOrderInStatus(customerId, "IN_DELIVERY").orderId();
        Long deliveryId = testData.insertDelivery(orderId, courierId, "PICKED_UP");

        rabbitTemplate.convertAndSend("amq.topic", "couriers." + courierId + ".deliveries." + deliveryId + ".position",
                "{\"latitude\":55.7512,\"longitude\":37.6184,\"speedKmh\":21}".getBytes(StandardCharsets.UTF_8));

        awaitTrue(() -> !trackingService.getTrackForCourier(courierId, deliveryId, 10).positions().isEmpty());
        assertEquals(55.7512, trackingService.getTrackForCourier(courierId, deliveryId, 10).positions().getFirst().latitude(), 1e-6);
    }

    @Test
    void shouldIgnorePositionForSomeoneElsesDelivery() throws Exception {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();
        Long stranger = testData.insertCourier();
        Long orderId = testData.insertOrderInStatus(customerId, "IN_DELIVERY").orderId();
        Long deliveryId = testData.insertDelivery(orderId, courierId, "PICKED_UP");

        rabbitTemplate.convertAndSend("amq.topic", "couriers." + stranger + ".deliveries." + deliveryId + ".position",
                "{\"latitude\":10,\"longitude\":10}".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1500);

        assertTrue(trackingService.getTrackForCourier(courierId, deliveryId, 10).positions().isEmpty());
    }

    @Test
    void shouldLetCourierInWithOwnTokenOnly() throws Exception {
        long courierId = testData.insertCourier();
        String token = jwt.tokenFor(courierId, "COURIER");

        assertEquals("allow", post("user", Map.of("username", String.valueOf(courierId), "password", token)));
        assertEquals("deny", post("user", Map.of("username", String.valueOf(courierId + 1), "password", token)),
                "логин обязан совпасть с sub токена");
        assertEquals("deny", post("user", Map.of("username", "1", "password", jwt.tokenFor(1, "CUSTOMER"))),
                "клиенту на MQTT нечего делать");
        assertEquals("deny", post("user", Map.of("username", String.valueOf(courierId), "password", "not-a-jwt")));
    }

    @Test
    void shouldLetCourierWriteOnlyToOwnTopics() throws Exception {
        assertEquals("allow", post("topic", Map.of("username", "42", "resource", "topic", "name", "amq.topic",
                "permission", "write", "routing_key", "couriers.42.deliveries.7.position")));
        assertEquals("deny", post("topic", Map.of("username", "42", "resource", "topic", "name", "amq.topic",
                "permission", "write", "routing_key", "couriers.43.deliveries.7.position")));
        assertEquals("deny", post("topic", Map.of("username", "42", "resource", "topic", "name", "amq.topic",
                "permission", "read", "routing_key", "couriers.42.deliveries.7.position")));
        assertEquals("allow", post("resource", Map.of("username", "42", "resource", "exchange", "name", "amq.topic", "permission", "write")));
        assertEquals("deny", post("resource", Map.of("username", "42", "resource", "queue", "name", "notifications.email", "permission", "read")));
        assertEquals("allow", post("vhost", Map.of("username", "42", "vhost", "/")));
    }

    private String post(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/rabbitmq/" + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("точка не записалась");
    }
}
