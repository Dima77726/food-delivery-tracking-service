package com.dima.fooddelivery.support;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.order.Delivery;
import com.dima.fooddelivery.order.Order;
import com.dima.fooddelivery.order.OrderClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис заказов в памяти — вместо gRPC-клиента к order-service.
 *
 * <p>Сервису трека от заказа нужно немного: чей он и какая у него доставка. Тест заводит
 * заказ и доставку здесь, а сервис читает их через тот же {@link OrderClient}, что и в бою.
 */
public class TestOrders implements OrderClient {

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final Map<Long, Delivery> deliveries = new ConcurrentHashMap<>();

    public void addOrder(Long orderId, Long customerId, String status) {
        orders.put(orderId, new Order(
                orderId, customerId, 1L, status, new BigDecimal("450.00"), OffsetDateTime.now(), List.of(), null
        ));
    }

    public void addDelivery(Long deliveryId, Long orderId, Long courierId, String status) {
        Delivery delivery = new Delivery(deliveryId, orderId, courierId, status);
        deliveries.put(deliveryId, delivery);
        orders.computeIfPresent(orderId, (id, order) -> new Order(
                order.id(), order.customerId(), order.restaurantId(), order.status(), order.totalAmount(),
                order.createdAt(), order.items(), delivery
        ));
    }

    @Override
    public Order requireOrder(Long orderId) {
        Order order = orders.get(orderId);

        if (order == null) {
            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден");
        }

        return order;
    }

    @Override
    public Delivery requireDelivery(Long deliveryId) {
        Delivery delivery = deliveries.get(deliveryId);

        if (delivery == null) {
            throw new ResourceNotFoundException("Доставка с id=" + deliveryId + " не найдена");
        }

        return delivery;
    }
}
