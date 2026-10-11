package com.dima.fooddelivery.tracking.api;

import java.util.List;

/**
 * Трек: точки от свежей к старой.
 *
 * <p>Порядок не случайный и не выбран в контроллере — он задан порядком хранения строк
 * в Cassandra (CLUSTERING ORDER BY recorded_at DESC). Перевернуть его в коде можно, но это
 * означало бы разложить весь ответ в памяти ради косметики.
 *
 * <p>{@code size} отдаётся явно: клиент, попросивший сто точек и получивший двадцать, должен
 * видеть это по ответу, а не по длине массива.
 */
public record TrackResponse(
        Long deliveryId,
        int size,
        List<PositionResponse> positions
) {
}
