package com.dima.fooddelivery.order;

/**
 * Доставка глазами сервиса трека.
 *
 * @param courierId курьер; {@code null}, пока никто не взял доставку
 */
public record Delivery(
        Long id,
        Long orderId,
        Long courierId,
        String status
) {

    public static final String PICKED_UP = "PICKED_UP";

    public boolean assignedTo(Long candidateCourierId) {
        return courierId != null && courierId.equals(candidateCourierId);
    }

    public boolean isPickedUp() {
        return PICKED_UP.equals(status);
    }
}
