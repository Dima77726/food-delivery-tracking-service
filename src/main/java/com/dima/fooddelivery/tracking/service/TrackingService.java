package com.dima.fooddelivery.tracking.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.order.Delivery;
import com.dima.fooddelivery.order.Order;
import com.dima.fooddelivery.order.OrderClient;
import com.dima.fooddelivery.tracking.live.LiveTrackPublisher;
import com.dima.fooddelivery.tracking.domain.CourierPosition;
import com.dima.fooddelivery.tracking.domain.CourierTrack;
import com.dima.fooddelivery.tracking.persistence.CourierTrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


/**
 * Модуль Tracking. Трек курьера по доставке.
 *
 * <p>Права проверяются по данным сервиса заказов, точки пишутся в Cassandra. Заказ и доставку
 * сервис трека спрашивает у order-service по gRPC ({@link OrderClient}).
 *
 * <p><b>Почему проверка прав стоит перед каждой записью, хотя запись дешёвая.</b> Записи
 * в Cassandra не откатываются, и «сначала запишем, потом разберёмся» здесь означало бы, что
 * посторонний может засорять чужой трек. Проверка стоит одного вызова сервиса заказов
 * и окупается тем, что в партиции доставки лежат только точки её курьера.
 *
 * <p>{@code @Transactional} здесь нет по той же причине, что и в модуле отзывов: транзакции,
 * которая охватила бы и сервис заказов, и Cassandra, не бывает.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingService {

    private final CourierTrackRepository courierTrackRepository;
    private final OrderClient orderClient;
    private final LiveTrackPublisher liveTrack;

    /**
     * Записывает точку курьера.
     *
     * @throws AccessDeniedForResourceException если доставка назначена другому курьеру
     * @throws BusinessRuleViolationException   если заказ ещё не забран или уже доставлен
     */
    public CourierPosition reportPosition(
            Long courierId,
            Long deliveryId,
            double latitude,
            double longitude,
            Double speedKmh
    ) {
        Delivery delivery = requireCourierDelivery(courierId, deliveryId);

        // Трек имеет смысл только пока заказ едет. До того курьер ещё не забрал его,
        // после — везти уже нечего, и точки были бы слежкой за человеком без причины.
        if (!delivery.isPickedUp()) {
            throw new BusinessRuleViolationException(
                    "Трек пишется только для доставки в статусе PICKED_UP. Текущий статус="
                            + delivery.status()
            );
        }

        // Время ставит сервер, а не телефон курьера, и генерирует его репозиторий вместе
        // с ключом строки. Часы на устройстве врут, а метка времени здесь ещё и ключ
        // кластеризации: сдвинутые часы переставили бы точки местами внутри партиции.
        CourierPosition position = courierTrackRepository.save(
                deliveryId,
                courierId,
                latitude,
                longitude,
                speedKmh
        );

        log.debug("Точка трека записана: deliveryId={}, courierId={}", deliveryId, courierId);

        // Подписчики заказа получают точку сразу, без опроса. Сначала запись, потом рассылка:
        // клиент, который после уведомления перечитает трек, обязан найти в нём эту точку.
        liveTrack.publish(delivery.orderId(), position);

        return position;
    }

    /** Трек глазами курьера: своя доставка. */
    public CourierTrack getTrackForCourier(Long courierId, Long deliveryId, int limit) {
        requireCourierDelivery(courierId, deliveryId);

        return new CourierTrack(deliveryId, courierTrackRepository.findLastPositions(deliveryId, limit));
    }

    /**
     * Трек глазами клиента: свой заказ.
     *
     * <p>Клиент не знает идентификатора доставки и знать его не должен — он оперирует заказом.
     * Доставку заказа сервис заказов отдаёт вместе с ним.
     */
    public CourierTrack getTrackForCustomerOrder(Long customerId, Long orderId, int limit) {
        Order order = orderClient.requireOrder(orderId);

        if (!order.belongsToCustomer(customerId)) {
            log.warn("Попытка посмотреть трек чужого заказа: orderId={}, customerId={}", orderId, customerId);

            throw new AccessDeniedForResourceException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }

        Delivery delivery = order.delivery();

        if (delivery == null) {
            throw new ResourceNotFoundException("Доставка для заказа с id=" + orderId + " ещё не создана");
        }

        return new CourierTrack(delivery.id(), courierTrackRepository.findLastPositions(delivery.id(), limit));
    }

    private Delivery requireCourierDelivery(Long courierId, Long deliveryId) {
        Delivery delivery = orderClient.requireDelivery(deliveryId);

        if (!delivery.assignedTo(courierId)) {
            log.warn("Курьер обратился к чужой доставке: deliveryId={}, courierId={}", deliveryId, courierId);

            throw new AccessDeniedForResourceException("Эта доставка назначена другому курьеру");
        }

        return delivery;
    }
}
