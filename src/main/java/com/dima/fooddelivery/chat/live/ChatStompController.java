package com.dima.fooddelivery.chat.live;

import com.dima.fooddelivery.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * Кадры STOMP от клиента: «печатаю» и «прочитал». Это и есть двусторонняя часть WebSocket —
 * клиент не только слушает, но и шлёт в то же соединение.
 *
 * <p>Адреса — {@code /app/order.{orderId}.chat.typing} и {@code /app/order.{orderId}.chat.read}:
 * префикс {@code /app} ведёт кадр сюда, в обработчик, а не в брокер. Пользователь — тот, кто
 * прошёл CONNECT с токеном ({@code StompAuthInterceptor}).
 *
 * <p>Ответа у этих кадров нет: ошибку (не участник, заказа нет) клиенту сообщать незачем,
 * она только пишется в лог. Потерянный «печатает» ничего не ломает.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final ChatService chatService;

    @MessageMapping("order.{orderId}.chat.typing")
    public void typing(@DestinationVariable Long orderId, Authentication user) {
        chatService.typing(orderId, Long.valueOf(user.getName()), isAdmin(user));
    }

    @MessageMapping("order.{orderId}.chat.read")
    public void read(@DestinationVariable Long orderId, @Payload ReadReceipt receipt, Authentication user) {
        chatService.markRead(orderId, Long.valueOf(user.getName()), isAdmin(user), receipt.upTo());
    }

    @MessageExceptionHandler
    public void onError(Exception exception) {
        log.warn("Кадр чата отклонён: {}", exception.getMessage());
    }

    private static boolean isAdmin(Authentication user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(ADMIN_AUTHORITY::equals);
    }

    /** Тело кадра «прочитал»: последнее сообщение, которое участник увидел. */
    public record ReadReceipt(UUID upTo) {
    }
}
