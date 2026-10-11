package com.dima.fooddelivery.support;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Заказы, доставки и пользователи для тестов трека. Ничего из этого сервису трека
 * не принадлежит: заказы и доставки «живут» в {@link TestOrders}, пользователи — это просто
 * идентификаторы.
 *
 * <p>Идентификаторы — из растущего счётчика: Cassandra откатом не очищается, и уникальность
 * между тестами даёт только то, что номера не повторяются.
 */
public class TestDataFactory {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L + 3_000_000_000L);

    private final TestOrders orders;

    public TestDataFactory(TestOrders orders) {
        this.orders = orders;
    }

    public Long insertCustomer() {
        return IDS.incrementAndGet();
    }

    public Long insertCourier() {
        return IDS.incrementAndGet();
    }

    public OrderContext insertOrderInStatus(Long customerId, String status) {
        Long orderId = IDS.incrementAndGet();
        orders.addOrder(orderId, customerId, status);

        return new OrderContext(orderId);
    }

    public Long insertDelivery(Long orderId, Long courierId, String status) {
        Long deliveryId = IDS.incrementAndGet();
        orders.addDelivery(deliveryId, orderId, courierId, status);

        return deliveryId;
    }

    public record OrderContext(Long orderId) {
    }
}
