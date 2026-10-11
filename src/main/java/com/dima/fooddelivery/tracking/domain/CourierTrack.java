package com.dima.fooddelivery.tracking.domain;

import java.util.List;

/**
 * Трек одной доставки: её идентификатор и точки.
 *
 * <p>Идентификатор здесь не для красоты. Клиент спрашивает трек по номеру заказа и номера
 * доставки не знает; без этого поля пустой ответ невозможно было бы отличить от ответа
 * про несуществующую доставку.
 */
public record CourierTrack(
        Long deliveryId,
        List<CourierPosition> positions
) {
}
