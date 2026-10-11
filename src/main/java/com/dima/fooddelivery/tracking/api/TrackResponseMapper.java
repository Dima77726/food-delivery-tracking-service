package com.dima.fooddelivery.tracking.api;

import com.dima.fooddelivery.tracking.domain.CourierPosition;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

public final class TrackResponseMapper {

    public static PositionResponse toResponse(CourierPosition position) {
        return new PositionResponse(
                position.deliveryId(),
                position.courierId(),
                OffsetDateTime.ofInstant(position.recordedAt(), ZoneId.systemDefault()),
                position.latitude(),
                position.longitude(),
                position.speedKmh()
        );
    }

    public static TrackResponse toTrack(Long deliveryId, List<CourierPosition> positions) {
        return new TrackResponse(
                deliveryId,
                positions.size(),
                positions.stream().map(TrackResponseMapper::toResponse).toList()
        );
    }

    private TrackResponseMapper() {
    }
}
