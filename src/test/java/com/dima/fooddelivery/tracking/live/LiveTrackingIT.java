package com.dima.fooddelivery.tracking.live;

import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory;
import com.dima.fooddelivery.tracking.api.PositionResponse;
import com.dima.fooddelivery.tracking.service.TrackingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Живой трек: настоящий WebSocket, настоящий STOMP и ретранслятор в RabbitMQ.
 *
 * <p>Клиент подключается так же, как подключился бы браузер: токен в заголовке кадра
 * CONNECT, подписка на адрес своего заказа. Точку записывает курьер — и она приходит
 * подписчику через брокер, без единого запроса с его стороны.
 */
class LiveTrackingIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private TrackingService trackingService;

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
    void shouldPushCourierPositionToOrderOwner() throws Exception {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, "IN_DELIVERY");
        Long deliveryId = testData.insertDelivery(order.orderId(), courierId, "PICKED_UP");

        StompSession session = connect(jwt.tokenFor(customerId, "CUSTOMER"));
        BlockingQueue<PositionResponse> received = subscribe(session, LiveTrackPublisher.destination(order.orderId()));

        // Подписка доходит до брокера асинхронно; точка, отправленная раньше, ушла бы в пустоту.
        Thread.sleep(500);

        trackingService.reportPosition(courierId, deliveryId, 55.751244, 37.618423, 18.5);

        PositionResponse position = received.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertAll(
                () -> assertNotNull(position, "точка не пришла подписчику"),
                () -> assertEquals(deliveryId, position.deliveryId()),
                () -> assertEquals(55.751244, position.latitude(), 0.000001),
                () -> assertEquals(18.5, position.speedKmh())
        );
    }

    /** Подписка на чужой заказ закрывает соединение и ничего не доставляет. */
    @Test
    void shouldRejectSubscriptionToForeignOrder() throws Exception {
        Long owner = testData.insertCustomer();
        Long stranger = testData.insertCustomer();
        Long courierId = testData.insertCourier();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(owner, "IN_DELIVERY");
        Long deliveryId = testData.insertDelivery(order.orderId(), courierId, "PICKED_UP");

        ErrorCapturingHandler handler = new ErrorCapturingHandler();
        StompSession session = connect(jwt.tokenFor(stranger, "CUSTOMER"), handler);
        BlockingQueue<PositionResponse> received = subscribe(session, LiveTrackPublisher.destination(order.orderId()));

        Thread.sleep(500);
        trackingService.reportPosition(courierId, deliveryId, 55.75, 37.61, null);

        assertAll(
                () -> assertNotNull(handler.error.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "ожидался кадр ERROR"),
                () -> assertNull(received.poll(1, TimeUnit.SECONDS), "чужой трек не должен доставляться")
        );
    }

    @Test
    void shouldRejectConnectionWithoutToken() {
        assertThrows(Exception.class, () -> connect(null));
    }

    private StompSession connect(String token) throws Exception {
        return connect(token, new ErrorCapturingHandler());
    }

    private StompSession connect(String token, ErrorCapturingHandler handler) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }

        StompSession session = client.connectAsync(
                "ws://localhost:" + port + LiveTrackingConfig.ENDPOINT,
                new WebSocketHttpHeaders(),
                connectHeaders,
                handler
        ).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        sessions.add(session);

        return session;
    }

    private static BlockingQueue<PositionResponse> subscribe(StompSession session, String destination) {
        BlockingQueue<PositionResponse> received = new LinkedBlockingQueue<>();

        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return PositionResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((PositionResponse) payload);
            }
        });

        return received;
    }

    /** Запоминает кадр ERROR от сервера — так STOMP сообщает об отказе. */
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
