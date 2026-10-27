package com.dima.fooddelivery.tracking.live;

import com.dima.fooddelivery.chat.service.ChatParticipants;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.security.SecurityConfig;
import com.dima.fooddelivery.order.Order;
import com.dima.fooddelivery.order.OrderClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Аутентификация и права на STOMP-кадрах.
 *
 * <p><b>Почему не на HTTP-рукопожатии.</b> Браузерный API WebSocket не умеет добавлять
 * заголовки к рукопожатию, а токен в адресе ({@code ?token=...}) оседает в логах шлюза
 * и прокси. Поэтому рукопожатие открыто, а токен приходит заголовком {@code Authorization}
 * кадра CONNECT — того же формата, что и в REST. Без валидного токена соединение
 * закрывается кадром ERROR.
 *
 * <p><b>Подписка — только на свой заказ.</b> Адрес {@code /topic/order.42.track} знает
 * любой, кто умеет считать, поэтому каждая подписка проверяется вопросом к сервису заказов:
 * чей заказ 42. Администратору можно всё. Чужой заказ — ERROR, соединение закрывается.
 *
 * <p><b>Чат заказа.</b> На {@code /topic/order.42.chat} подписываются участники чата — клиент,
 * курьер этой доставки и поддержка (см. {@code ChatParticipants}).
 *
 * <p><b>Писать можно только служебные кадры чата.</b> Точки отправляет курьер по REST, где
 * проверяются его права на доставку, сообщения чата — тоже REST. Кадром SEND клиент шлёт лишь
 * «печатаю» и «прочитал» на {@code /app/order.42.chat.typing|read} — в обработчик, а не в брокер.
 * SEND прямо в {@code /topic/...} отклоняется: иначе любой подписчик мог бы рисовать курьера
 * где захочет или писать в чат от чужого имени.
 */
@Slf4j
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Pattern TRACK_TOPIC = Pattern.compile("^/topic/order\\.(\\d+)\\.track$");
    private static final Pattern CHAT_TOPIC = Pattern.compile("^/topic/order\\.(\\d+)\\.chat$");
    private static final Pattern CHAT_SEND = Pattern.compile("^/app/order\\.(\\d+)\\.chat\\.(typing|read)$");
    private static final String BEARER = "Bearer ";
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final JwtDecoder jwtDecoder;
    private final OrderClient orderClient;
    private final ChatParticipants chatParticipants;

    public StompAuthInterceptor(JwtDecoder jwtDecoder, OrderClient orderClient, ChatParticipants chatParticipants) {
        this.jwtDecoder = jwtDecoder;
        this.orderClient = orderClient;
        this.chatParticipants = chatParticipants;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        switch (accessor.getCommand()) {
            case CONNECT -> accessor.setUser(authenticate(accessor));
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case SEND -> authorizeSend(accessor);
            default -> {
                // DISCONNECT, UNSUBSCRIBE, ACK — без проверок.
            }
        }

        return message;
    }

    private Authentication authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");

        if (header == null || !header.startsWith(BEARER)) {
            throw new MessageDeliveryException("Нужен заголовок Authorization: Bearer <access-токен>");
        }

        try {
            Jwt jwt = jwtDecoder.decode(header.substring(BEARER.length()));

            return SecurityConfig.authentication(jwt);
        } catch (JwtException exception) {
            log.warn("WebSocket: отклонён токен: {}", exception.getMessage());

            throw new MessageDeliveryException("Недействительный токен");
        }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        AbstractAuthenticationToken authentication = requireUser(accessor);
        String destination = accessor.getDestination() == null ? "" : accessor.getDestination();

        Matcher track = TRACK_TOPIC.matcher(destination);
        if (track.matches()) {
            authorizeTrack(authentication, Long.valueOf(track.group(1)));
            return;
        }

        Matcher chat = CHAT_TOPIC.matcher(destination);
        if (chat.matches()) {
            requireChatParticipant(authentication, Long.valueOf(chat.group(1)));
            return;
        }

        throw new MessageDeliveryException(
                "Подписаться можно только на /topic/order.{orderId}.track и /topic/order.{orderId}.chat");
    }

    private void authorizeTrack(AbstractAuthenticationToken authentication, Long orderId) {
        if (isAdmin(authentication)) {
            return;
        }

        Order order = orderClient.requireOrder(orderId);

        if (!order.belongsToCustomer(Long.valueOf(authentication.getName()))) {
            log.warn("WebSocket: подписка на чужой заказ: orderId={}, userId={}", orderId, authentication.getName());

            throw new MessageDeliveryException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }
    }

    /** Кадры от клиента — только «печатаю» и «прочитал» в чат, где он участник. */
    private void authorizeSend(StompHeaderAccessor accessor) {
        AbstractAuthenticationToken authentication = requireUser(accessor);
        String destination = accessor.getDestination() == null ? "" : accessor.getDestination();

        Matcher send = CHAT_SEND.matcher(destination);
        if (!send.matches()) {
            throw new MessageDeliveryException("Отправлять можно только /app/order.{orderId}.chat.typing|read");
        }

        requireChatParticipant(authentication, Long.valueOf(send.group(1)));
    }

    private void requireChatParticipant(AbstractAuthenticationToken authentication, Long orderId) {
        try {
            chatParticipants.require(orderId, Long.valueOf(authentication.getName()), isAdmin(authentication));
        } catch (ResourceNotFoundException exception) {
            log.warn("WebSocket: не участник чата: orderId={}, userId={}", orderId, authentication.getName());

            throw new MessageDeliveryException("Чат заказа с id=" + orderId + " недоступен");
        }
    }

    private static AbstractAuthenticationToken requireUser(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();

        if (!(principal instanceof AbstractAuthenticationToken authentication)) {
            throw new MessageDeliveryException("Сначала CONNECT с токеном");
        }

        return authentication;
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
