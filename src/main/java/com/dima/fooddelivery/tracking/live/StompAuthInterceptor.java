package com.dima.fooddelivery.tracking.live;

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
 * <p><b>Писать клиенту некуда.</b> Точки отправляет курьер по REST, где проверяются его права
 * на доставку. Кадр SEND от клиента отклоняется — иначе любой подписчик мог бы рисовать
 * курьера где захочет.
 */
@Slf4j
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Pattern ORDER_TOPIC = Pattern.compile("^/topic/order\\.(\\d+)\\.track$");
    private static final String BEARER = "Bearer ";
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final JwtDecoder jwtDecoder;
    private final OrderClient orderClient;

    public StompAuthInterceptor(JwtDecoder jwtDecoder, OrderClient orderClient) {
        this.jwtDecoder = jwtDecoder;
        this.orderClient = orderClient;
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
            case SEND -> throw new MessageDeliveryException("Отправлять в живой трек может только сервис");
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
        Principal principal = accessor.getUser();

        if (!(principal instanceof AbstractAuthenticationToken authentication)) {
            throw new MessageDeliveryException("Сначала CONNECT с токеном");
        }

        String destination = accessor.getDestination();
        Matcher matcher = destination == null ? null : ORDER_TOPIC.matcher(destination);

        if (matcher == null || !matcher.matches()) {
            throw new MessageDeliveryException("Подписаться можно только на /topic/order.{orderId}.track");
        }

        if (isAdmin(authentication)) {
            return;
        }

        Long orderId = Long.valueOf(matcher.group(1));
        Order order = orderClient.requireOrder(orderId);

        if (!order.belongsToCustomer(Long.valueOf(authentication.getName()))) {
            log.warn("WebSocket: подписка на чужой заказ: orderId={}, userId={}", orderId, authentication.getName());

            throw new MessageDeliveryException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
