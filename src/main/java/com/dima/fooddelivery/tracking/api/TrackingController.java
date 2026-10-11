package com.dima.fooddelivery.tracking.api;

import com.dima.fooddelivery.tracking.domain.CourierTrack;
import com.dima.fooddelivery.tracking.service.TrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Трек курьера: запись точек и чтение маршрута.
 *
 * <p>Два разных чтения одного и того же трека — курьером по доставке и клиентом по заказу —
 * не дублирование. У них разные права, разные идентификаторы в пути и разный смысл: курьер
 * смотрит свою работу, клиент - где его еда.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "Tracking", description = "Трек курьера (Cassandra)")
@SecurityRequirement(name = "bearer-jwt")
public class TrackingController {

    private static final int DEFAULT_TRACK_LIMIT = 100;
    private static final int MAX_TRACK_LIMIT = 1000;

    private final TrackingService trackingService;

    /**
     * 202, а не 201: точка трека — телеметрия, а не созданный ресурс, и ссылки на неё
     * не существует. Клиенту важно только то, что сервер её принял.
     */
    @PostMapping("/api/v1/couriers/{courierId}/deliveries/{deliveryId}/positions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Отправить текущую координату курьера")
    public PositionResponse reportPosition(
            @Positive(message = "courierId должен быть положительным числом")
            @PathVariable Long courierId,

            @Positive(message = "deliveryId должен быть положительным числом")
            @PathVariable Long deliveryId,

            @Valid @RequestBody ReportPositionRequest request
    ) {
        return TrackResponseMapper.toResponse(trackingService.reportPosition(
                courierId,
                deliveryId,
                request.latitude(),
                request.longitude(),
                request.speedKmh()
        ));
    }

    @GetMapping("/api/v1/couriers/{courierId}/deliveries/{deliveryId}/track")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Трек своей доставки глазами курьера")
    public TrackResponse getCourierTrack(
            @Positive(message = "courierId должен быть положительным числом")
            @PathVariable Long courierId,

            @Positive(message = "deliveryId должен быть положительным числом")
            @PathVariable Long deliveryId,

            @Min(value = 1, message = "limit не меньше 1")
            @Max(value = MAX_TRACK_LIMIT, message = "limit не больше " + MAX_TRACK_LIMIT)
            @RequestParam(defaultValue = "" + DEFAULT_TRACK_LIMIT) int limit
    ) {
        CourierTrack track = trackingService.getTrackForCourier(courierId, deliveryId, limit);

        return TrackResponseMapper.toTrack(track.deliveryId(), track.positions());
    }

    @GetMapping("/api/v1/customers/{customerId}/orders/{orderId}/track")
    @PreAuthorize("@access.isSelf(#customerId)")
    @Operation(summary = "Где сейчас курьер с моим заказом")
    public TrackResponse getOrderTrack(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId,

            @Min(value = 1, message = "limit не меньше 1")
            @Max(value = MAX_TRACK_LIMIT, message = "limit не больше " + MAX_TRACK_LIMIT)
            @RequestParam(defaultValue = "" + DEFAULT_TRACK_LIMIT) int limit
    ) {
        CourierTrack track = trackingService.getTrackForCustomerOrder(customerId, orderId, limit);

        return TrackResponseMapper.toTrack(track.deliveryId(), track.positions());
    }
}
