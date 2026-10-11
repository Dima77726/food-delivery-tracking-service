package com.dima.fooddelivery.order;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;


/**
 * Заказы глазами сервиса трека: вопросы к сервису заказов.
 *
 * <p>Интерфейс, а не gRPC-стаб в модулях: отзывам и треку незачем знать, что заказ живёт
 * в другом процессе. Исключения доменные, поэтому обработчик ошибок
 * отвечает теми же кодами, что и в монолите, где трек жил раньше.
 */
public interface OrderClient {

    /**
     * @throws ResourceNotFoundException    если заказа нет
     * @throws UpstreamUnavailableException если сервис заказов не ответил
     */
    Order requireOrder(Long orderId);

    /**
     * @throws ResourceNotFoundException    если доставки нет
     * @throws UpstreamUnavailableException если сервис заказов не ответил
     */
    Delivery requireDelivery(Long deliveryId);
}
