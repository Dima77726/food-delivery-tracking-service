package com.dima.fooddelivery.tracking.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory;
import com.dima.fooddelivery.tracking.domain.CourierPosition;
import com.dima.fooddelivery.tracking.domain.CourierTrack;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Трек курьера на настоящей Cassandra.
 *
 * <p>Ключевая проверка — порядок точек. Он задан не сортировкой в запросе, а объявлением
 * CLUSTERING ORDER в DDL, и ошибка в нём проявилась бы только здесь: с моком драйвера
 * или с in-memory заглушкой порядок определял бы код теста, а не база.
 */
class CourierTrackIT extends AbstractIntegrationTest {

    @Autowired
    private TrackingService trackingService;

    @Test
    void shouldStoreAndReturnPositionsNewestFirst() {
        Courier courier = pickedUpDelivery();

        trackingService.reportPosition(courier.courierId(), courier.deliveryId(), 55.75, 37.61, 20.0);
        trackingService.reportPosition(courier.courierId(), courier.deliveryId(), 55.76, 37.62, 25.0);
        trackingService.reportPosition(courier.courierId(), courier.deliveryId(), 55.77, 37.63, 30.0);

        CourierTrack track = trackingService.getTrackForCourier(courier.courierId(), courier.deliveryId(), 10);

        assertAll(
                () -> assertEquals(3, track.positions().size()),
                () -> assertEquals(
                        55.77,
                        track.positions().get(0).latitude(),
                        0.0001,
                        "первой обязана идти самая свежая точка"
                ),
                () -> assertEquals(55.75, track.positions().get(2).latitude(), 0.0001),
                // Не isAfter, а !isBefore: три вызова подряд легко укладываются в одну
                // миллисекунду, и различает их не время, а timeuuid. Порядок при этом
                // остаётся правильным — что и проверяют широты выше.
                () -> assertTrue(
                        !track.positions().get(0).recordedAt()
                                .isBefore(track.positions().get(2).recordedAt()),
                        "свежая точка не может быть старше давней"
                )
        );
    }

    /**
     * LIMIT в Cassandra ограничивает чтение партиции, а не отбрасывает лишнее после него.
     * Здесь важно, что урезается именно хвост: остаются свежие точки, а не случайные.
     */
    @Test
    void shouldLimitTrackToRequestedSize() {
        Courier courier = pickedUpDelivery();

        for (int i = 0; i < 5; i++) {
            trackingService.reportPosition(courier.courierId(), courier.deliveryId(), 55.0 + i, 37.0, null);
        }

        CourierTrack track = trackingService.getTrackForCourier(courier.courierId(), courier.deliveryId(), 2);

        assertAll(
                () -> assertEquals(2, track.positions().size()),
                () -> assertEquals(59.0, track.positions().get(0).latitude(), 0.0001)
        );
    }

    /**
     * Скорость может не прийти: телефон отдаёт её не всегда. Проверка про то, что null
     * доезжает до ответа именно как null, а не превращается в ноль — то есть в утверждение
     * «курьер стоит», которого никто не делал.
     */
    @Test
    void shouldKeepMissingSpeedAsNull() {
        Courier courier = pickedUpDelivery();

        trackingService.reportPosition(courier.courierId(), courier.deliveryId(), 55.75, 37.61, null);

        CourierPosition position = trackingService
                .getTrackForCourier(courier.courierId(), courier.deliveryId(), 1)
                .positions()
                .get(0);

        assertNull(position.speedKmh());
    }

    @Test
    void shouldRejectPositionFromForeignCourier() {
        Courier courier = pickedUpDelivery();
        Long stranger = testData.insertCourier();

        assertThrows(
                AccessDeniedForResourceException.class,
                () -> trackingService.reportPosition(stranger, courier.deliveryId(), 55.75, 37.61, null)
        );
    }

    @Test
    void shouldRejectPositionForDeliveryThatIsNotPickedUpYet() {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();

        TestDataFactory.OrderContext order =
                testData.insertOrderInStatus(customerId, "READY_FOR_DELIVERY");
        Long deliveryId = testData.insertDelivery(order.orderId(), courierId, "ASSIGNED");

        assertThrows(
                BusinessRuleViolationException.class,
                () -> trackingService.reportPosition(courierId, deliveryId, 55.75, 37.61, null)
        );
    }

    /**
     * Клиент спрашивает трек по номеру заказа: номера доставки он не знает и знать не должен.
     */
    @Test
    void shouldReturnTrackToOrderOwnerByOrderId() {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();

        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, "IN_DELIVERY");
        Long deliveryId = testData.insertDelivery(order.orderId(), courierId, "PICKED_UP");

        trackingService.reportPosition(courierId, deliveryId, 55.75, 37.61, 15.0);

        CourierTrack track = trackingService.getTrackForCustomerOrder(customerId, order.orderId(), 10);

        assertAll(
                () -> assertEquals(deliveryId, track.deliveryId()),
                () -> assertEquals(1, track.positions().size())
        );
    }

    @Test
    void shouldRejectTrackOfForeignOrder() {
        Long owner = testData.insertCustomer();
        Long stranger = testData.insertCustomer();
        Long courierId = testData.insertCourier();

        TestDataFactory.OrderContext order = testData.insertOrderInStatus(owner, "IN_DELIVERY");
        testData.insertDelivery(order.orderId(), courierId, "PICKED_UP");

        assertThrows(
                AccessDeniedForResourceException.class,
                () -> trackingService.getTrackForCustomerOrder(stranger, order.orderId(), 10)
        );
    }

    /** Заказ в пути и доставка, уже забранная курьером, — состояние, в котором пишется трек. */
    private Courier pickedUpDelivery() {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();

        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, "IN_DELIVERY");
        Long deliveryId = testData.insertDelivery(order.orderId(), courierId, "PICKED_UP");

        return new Courier(courierId, deliveryId);
    }

    private record Courier(Long courierId, Long deliveryId) {
    }
}
