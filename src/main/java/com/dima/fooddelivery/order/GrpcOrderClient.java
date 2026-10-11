package com.dima.fooddelivery.order;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import com.dima.fooddelivery.grpc.order.v1.DeliveryView;
import com.dima.fooddelivery.grpc.order.v1.GetDeliveryRequest;
import com.dima.fooddelivery.grpc.order.v1.GetOrderRequest;
import com.dima.fooddelivery.grpc.order.v1.OrderItemView;
import com.dima.fooddelivery.grpc.order.v1.OrderQueryServiceGrpc;
import com.dima.fooddelivery.grpc.order.v1.OrderView;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link OrderClient} поверх gRPC. Сервер — order-service.
 *
 * <p>Устроен так же, как клиент прав на рестораны: дедлайн на каждый вызов, NOT_FOUND —
 * в «нет такого», всё прочее — в 503. Повторов нет: вызов стоит на пути запроса пользователя,
 * и повтор при недоступном сервере только удвоил бы ожидание.
 */
@Slf4j
@Component
public class GrpcOrderClient implements OrderClient {

    private final OrderQueryServiceGrpc.OrderQueryServiceBlockingStub stub;
    private final Duration deadline;

    @Autowired
    public GrpcOrderClient(
            GrpcChannelFactory channels,
            @Value("${app.grpc.orders.deadline:PT2S}") Duration deadline
    ) {
        this(channels.createChannel("orders"), deadline);
    }

    /** Для тестов: канал к серверу в памяти процесса вместо настоящего адреса. */
    GrpcOrderClient(Channel channel, Duration deadline) {
        this.stub = OrderQueryServiceGrpc.newBlockingStub(channel);
        this.deadline = deadline;
    }

    @Override
    public Order requireOrder(Long orderId) {
        try {
            return toOrder(withDeadline().getOrder(GetOrderRequest.newBuilder().setOrderId(orderId).build()));
        } catch (StatusRuntimeException exception) {
            throw translate("прочитать заказ", exception);
        }
    }

    @Override
    public Delivery requireDelivery(Long deliveryId) {
        try {
            return toDelivery(withDeadline().getDelivery(GetDeliveryRequest.newBuilder().setDeliveryId(deliveryId).build()));
        } catch (StatusRuntimeException exception) {
            throw translate("прочитать доставку", exception);
        }
    }

    private OrderQueryServiceGrpc.OrderQueryServiceBlockingStub withDeadline() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private RuntimeException translate(String action, StatusRuntimeException exception) {
        if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
            return new ResourceNotFoundException(exception.getStatus().getDescription());
        }

        log.error("Не удалось {}: сервис заказов ответил {}", action, exception.getStatus());

        return new UpstreamUnavailableException(
                "Сервис заказов недоступен, не удалось " + action + ". Повторите позже",
                exception
        );
    }

    private static Order toOrder(OrderView view) {
        return new Order(
                view.getId(),
                view.getCustomerId(),
                view.getRestaurantId(),
                view.getStatus(),
                new BigDecimal(view.getTotalAmount()),
                OffsetDateTime.parse(view.getCreatedAt()),
                view.getItemsList().stream().map(GrpcOrderClient::toItem).toList(),
                view.hasDelivery() ? toDelivery(view.getDelivery()) : null
        );
    }

    private static OrderItem toItem(OrderItemView view) {
        return new OrderItem(
                view.getMenuItemId(),
                view.getMenuItemName(),
                view.getQuantity(),
                new BigDecimal(view.getPrice())
        );
    }

    private static Delivery toDelivery(DeliveryView view) {
        return new Delivery(
                view.getId(),
                view.getOrderId(),
                view.hasCourierId() ? view.getCourierId() : null,
                view.getStatus()
        );
    }
}
