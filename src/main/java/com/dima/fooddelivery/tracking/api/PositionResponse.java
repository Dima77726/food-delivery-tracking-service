package com.dima.fooddelivery.tracking.api;

import java.time.OffsetDateTime;

public record PositionResponse(
        Long deliveryId,
        Long courierId,
        OffsetDateTime recordedAt,
        double latitude,
        double longitude,
        Double speedKmh
) {
}
