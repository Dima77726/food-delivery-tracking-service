package com.dima.fooddelivery.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Заказ глазами сервиса трека — то, что отдаёт по gRPC сервис заказов. Трек нужен только
 * владельцу заказа и только пока есть доставка; остальное из заказа здесь не используется.
 *
 * <p>Статус — строкой, как в контракте: новый статус не должен ронять клиента, который
 * о нём ещё не знает.
 *
 * @param delivery доставка заказа; {@code null}, пока её не создали
 */
public record Order(
        Long id,
        Long customerId,
        Long restaurantId,
        String status,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        List<OrderItem> items,
        Delivery delivery
) {

    public static final String DELIVERED = "DELIVERED";

    public static final String CANCELED = "CANCELED";

    public boolean belongsToCustomer(Long candidateCustomerId) {
        return customerId.equals(candidateCustomerId);
    }

    public boolean isDelivered() {
        return DELIVERED.equals(status);
    }

    public boolean isCanceled() {
        return CANCELED.equals(status);
    }
}
