package com.dima.fooddelivery.tracking.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Координата, присланная курьером.
 *
 * <p>Границы широты и долготы проверяются здесь, потому что больше их проверить негде:
 * в Cassandra колонка объявлена как double, и CHECK-ограничений в CQL не существует вовсе.
 * Всё, что реляционная база сделала бы сама, в этой части системы обязан сделать код.
 */
public record ReportPositionRequest(

        @NotNull(message = "широта обязательна")
        @DecimalMin(value = "-90.0", message = "широта в диапазоне от -90 до 90")
        @DecimalMax(value = "90.0", message = "широта в диапазоне от -90 до 90")
        @Schema(example = "55.751244")
        Double latitude,

        @NotNull(message = "долгота обязательна")
        @DecimalMin(value = "-180.0", message = "долгота в диапазоне от -180 до 180")
        @DecimalMax(value = "180.0", message = "долгота в диапазоне от -180 до 180")
        @Schema(example = "37.618423")
        Double longitude,

        @PositiveOrZero(message = "скорость не может быть отрицательной")
        @DecimalMax(value = "300.0", message = "скорость не больше 300 км/ч")
        @Schema(description = "Скорость в км/ч; можно не присылать", example = "24.5")
        Double speedKmh
) {
}
