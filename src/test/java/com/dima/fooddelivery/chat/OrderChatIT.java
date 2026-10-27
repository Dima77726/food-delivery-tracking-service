package com.dima.fooddelivery.chat;

import com.dima.fooddelivery.chat.live.ChatEvent;
import com.dima.fooddelivery.chat.live.ChatPublisher;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.tracking.live.LiveTrackingConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Чат заказа целиком: отправка по HTTP, хранение в Cassandra, доставка по WebSocket через
 * настоящий RabbitMQ, кадры «печатаю» и «прочитал» от клиента.
 */
class OrderChatIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void disconnect() {
        sessions.forEach(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        });
    }

    @Test
    void shouldDeliverCustomerMessageToCourierAndKeepItInHistory() throws Exception {
        Chat chat = newChat("IN_DELIVERY");

        BlockingQueue<ChatEvent> courierFrames = subscribe(connect(chat.courierToken()), chat.orderId());
        Thread.sleep(500);

        HttpResponse<String> sent = send(chat.customerToken(), chat.orderId(), "Я у подъезда", UUID.randomUUID());
        assertEquals(201, sent.statusCode(), sent.body());

        ChatEvent frame = courierFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertAll(
                () -> assertNotNull(frame, "сообщение не пришло курьеру"),
                () -> assertEquals(ChatEvent.Type.MESSAGE, frame.type()),
                () -> assertEquals("Я у подъезда", frame.text()),
                () -> assertEquals(chat.customerId(), frame.senderId())
        );

        String history = history(chat.courierToken(), chat.orderId()).body();
        assertEquals("Я у подъезда", JsonPath.read(history, "$.messages[0].text"));
        assertEquals("CUSTOMER", JsonPath.read(history, "$.messages[0].senderRole"));
    }

    /** Повтор с тем же clientMessageId — то же сообщение, без второй записи и второй рассылки. */
    @Test
    void shouldTreatRepeatedSendAsTheSameMessage() throws Exception {
        Chat chat = newChat("IN_DELIVERY");
        BlockingQueue<ChatEvent> customerFrames = subscribe(connect(chat.customerToken()), chat.orderId());
        Thread.sleep(500);

        UUID clientMessageId = UUID.randomUUID();
        String first = send(chat.courierToken(), chat.orderId(), "Буду через 5 минут", clientMessageId).body();
        String second = send(chat.courierToken(), chat.orderId(), "Буду через 5 минут", clientMessageId).body();

        assertEquals(JsonPath.<String>read(first, "$.messageId"), JsonPath.read(second, "$.messageId"));
        assertNotNull(customerFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        assertNull(customerFrames.poll(1, TimeUnit.SECONDS), "повтор не должен рассылаться второй раз");

        List<?> messages = JsonPath.read(history(chat.customerToken(), chat.orderId()).body(), "$.messages");
        assertEquals(1, messages.size());
    }

    @Test
    void shouldPassTypingAndReadFramesFromCourierToCustomer() throws Exception {
        Chat chat = newChat("IN_DELIVERY");
        BlockingQueue<ChatEvent> customerFrames = subscribe(connect(chat.customerToken()), chat.orderId());
        StompSession courier = connect(chat.courierToken());
        Thread.sleep(500);

        courier.send("/app/order." + chat.orderId() + ".chat.typing", Map.of());

        ChatEvent typing = customerFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertNotNull(typing, "«печатает» не пришло");
        assertEquals(ChatEvent.Type.TYPING, typing.type());
        assertEquals(chat.courierId(), typing.senderId());

        String messageId = JsonPath.read(send(chat.customerToken(), chat.orderId(), "Код домофона 42", UUID.randomUUID()).body(), "$.messageId");
        customerFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        courier.send("/app/order." + chat.orderId() + ".chat.read", Map.of("upTo", messageId));

        ChatEvent read = customerFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertNotNull(read, "«прочитано» не пришло");
        assertEquals(ChatEvent.Type.READ, read.type());
        assertEquals(messageId, read.upTo().toString());

        String history = history(chat.customerToken(), chat.orderId()).body();
        assertEquals(messageId, JsonPath.read(history, "$.reads['" + chat.courierId() + "']"));
    }

    @Test
    void shouldHideChatFromStranger() throws Exception {
        Chat chat = newChat("IN_DELIVERY");
        String stranger = jwt.tokenFor(testData.insertCourier(), "COURIER");

        assertEquals(404, send(stranger, chat.orderId(), "привет", UUID.randomUUID()).statusCode());
        assertEquals(404, history(stranger, chat.orderId()).statusCode());

        ErrorCapturingHandler handler = new ErrorCapturingHandler();
        StompSession session = connect(stranger, handler);
        session.subscribe(ChatPublisher.destination(chat.orderId()), new FrameCollector(new LinkedBlockingQueue<>()));

        assertNotNull(handler.error.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "ожидался кадр ERROR");
    }

    @Test
    void shouldLimitMessageRate() throws Exception {
        Chat chat = newChat("IN_DELIVERY");

        // Ёмкость ведра — 5: шестое подряд получает 429.
        for (int i = 0; i < 5; i++) {
            assertEquals(201, send(chat.customerToken(), chat.orderId(), "сообщение " + i, UUID.randomUUID()).statusCode());
        }
        HttpResponse<String> limited = send(chat.customerToken(), chat.orderId(), "ещё одно", UUID.randomUUID());

        assertEquals(429, limited.statusCode());
        assertNotNull(limited.headers().firstValue("Retry-After").orElse(null));
    }

    @Test
    void shouldCloseChatOfFinishedOrder() throws Exception {
        Chat chat = newChat("DELIVERED");

        assertEquals(409, send(chat.customerToken(), chat.orderId(), "спасибо", UUID.randomUUID()).statusCode());
        assertEquals(200, history(chat.customerToken(), chat.orderId()).statusCode());
    }

    @Test
    void shouldRejectBlankMessage() throws Exception {
        Chat chat = newChat("IN_DELIVERY");

        assertEquals(400, send(chat.customerToken(), chat.orderId(), "   ", UUID.randomUUID()).statusCode());
    }

    private Chat newChat(String orderStatus) {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();
        Long orderId = testData.insertOrderInStatus(customerId, orderStatus).orderId();
        testData.insertDelivery(orderId, courierId, "PICKED_UP");

        return new Chat(orderId, customerId, courierId,
                jwt.tokenFor(customerId, "CUSTOMER"), jwt.tokenFor(courierId, "COURIER"));
    }

    private HttpResponse<String> send(String token, Long orderId, String text, UUID clientMessageId) throws Exception {
        String body = "{\"text\":\"" + text + "\",\"clientMessageId\":\"" + clientMessageId + "\"}";
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/orders/" + orderId + "/chat/messages"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> history(String token, Long orderId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/orders/" + orderId + "/chat/messages"))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private StompSession connect(String token) throws Exception {
        return connect(token, new ErrorCapturingHandler());
    }

    private StompSession connect(String token, ErrorCapturingHandler handler) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = client.connectAsync(
                "ws://localhost:" + port + LiveTrackingConfig.ENDPOINT,
                new WebSocketHttpHeaders(),
                connectHeaders,
                handler
        ).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        sessions.add(session);
        return session;
    }

    private static BlockingQueue<ChatEvent> subscribe(StompSession session, Long orderId) {
        BlockingQueue<ChatEvent> frames = new LinkedBlockingQueue<>();
        session.subscribe(ChatPublisher.destination(orderId), new FrameCollector(frames));
        return frames;
    }

    private record Chat(Long orderId, Long customerId, Long courierId, String customerToken, String courierToken) {
    }

    private record FrameCollector(BlockingQueue<ChatEvent> frames) implements StompFrameHandler {

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return ChatEvent.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            frames.add((ChatEvent) payload);
        }
    }

    private static class ErrorCapturingHandler extends StompSessionHandlerAdapter {

        private final CompletableFuture<StompHeaders> error = new CompletableFuture<>();

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            error.complete(headers);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                    byte[] payload, Throwable exception) {
            error.complete(headers);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            error.complete(new StompHeaders());
        }
    }
}
