package com.dima.fooddelivery.chat.service;

import com.dima.fooddelivery.chat.domain.ChatRole;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.order.Order;
import com.dima.fooddelivery.order.OrderClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Кто участвует в чате заказа: клиент этого заказа, курьер, назначенный на его доставку,
 * и поддержка (администратор).
 *
 * <p>Решает отношение к заказу, а не роль в токене: роль COURIER есть у всех курьеров, а писать
 * в чат может только тот, кто везёт этот заказ. Ответ — по данным сервиса заказов (gRPC),
 * поэтому назначение курьера видно сразу, без перевыпуска токена.
 *
 * <p>Посторонний получает «заказ не найден», а не «доступ запрещён»: чат — не тот случай, где
 * полезно подтверждать, что заказ с таким номером существует.
 */
@Component
@RequiredArgsConstructor
public class ChatParticipants {

    private final OrderClient orderClient;

    /**
     * @throws ResourceNotFoundException если заказа нет или пользователь в нём не участвует
     */
    public Participation require(Long orderId, Long userId, boolean admin) {
        Order order = orderClient.requireOrder(orderId);

        ChatRole role;
        if (order.belongsToCustomer(userId)) {
            role = ChatRole.CUSTOMER;
        } else if (order.delivery() != null && order.delivery().assignedTo(userId)) {
            role = ChatRole.COURIER;
        } else if (admin) {
            role = ChatRole.SUPPORT;
        } else {
            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден");
        }

        return new Participation(role, order.isDelivered() || order.isCanceled());
    }

    /**
     * @param role   кем пользователь приходится заказу
     * @param closed заказ завершён: читать переписку можно, писать — уже нет
     */
    public record Participation(ChatRole role, boolean closed) {
    }
}
