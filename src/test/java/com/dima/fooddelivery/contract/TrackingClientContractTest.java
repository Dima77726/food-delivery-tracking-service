package com.dima.fooddelivery.contract;

import com.dima.fooddelivery.client.api.TrackingApi;
import com.dima.fooddelivery.client.model.ReportPositionRequest;
import com.dima.fooddelivery.tracking.api.TrackingController;
import com.dima.fooddelivery.tracking.domain.CourierPosition;
import com.dima.fooddelivery.tracking.domain.CourierTrack;
import com.dima.fooddelivery.tracking.service.TrackingService;
import com.dima.fooddelivery.client.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сгенерированный SDK вызывает настоящий контроллер трека через MockMvc transport:
 * маршруты, коды ответа и JSON без Cassandra. Переехал из модуля {@code client} вместе
 * с треком — контракт проверяется против того сервиса, который его отдаёт.
 */
class TrackingClientContractTest {

    private final TrackingService tracking = mock(TrackingService.class);
    private ApiClient client;

    @BeforeEach
    void setUp() {
        var mvc = MockMvcBuilders.standaloneSetup(new TrackingController(tracking)).build();
        client = new ApiClient(ApiClient.buildRestClientBuilder()
                .requestFactory(new MockMvcClientHttpRequestFactory(mvc)).build());
    }

    @Test
    void trackingPreservesAcceptedStatusAndMissingSpeed() {
        var point = new CourierPosition(2L, 1L, Instant.parse("2026-09-09T12:00:00Z"), 55.75, 37.61, null);
        when(tracking.reportPosition(1L, 2L, 55.75, 37.61, null)).thenReturn(point);
        when(tracking.getTrackForCourier(1L, 2L, 100)).thenReturn(new CourierTrack(2L, List.of(point)));
        when(tracking.getTrackForCustomerOrder(3L, 4L, 7)).thenReturn(new CourierTrack(2L, List.of(point)));
        var api = new TrackingApi(client);
        var accepted = api.reportPositionWithHttpInfo(1L, 2L,
                new ReportPositionRequest().latitude(55.75).longitude(37.61));
        assertEquals(202, accepted.getStatusCode().value());
        assertNull(accepted.getBody().getSpeedKmh());
        assertEquals(1, api.getCourierTrack(1L, 2L, null).getSize());
        assertEquals(2L, api.getOrderTrack(3L, 4L, 7).getDeliveryId());
        verify(tracking).getTrackForCourier(1L, 2L, 100);
    }
}
