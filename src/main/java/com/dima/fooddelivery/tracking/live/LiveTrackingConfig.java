package com.dima.fooddelivery.tracking.live;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket со STOMP для живого трека и чата заказа.
 *
 * <p><b>Зачем WebSocket.</b> Без него клиент узнаёт о новой точке, только спросив: опрос
 * раз в пару секунд — это тысячи пустых запросов на каждого, кто смотрит на карту. Здесь
 * соединение одно и держится открытым, а точки приходят сами, в момент записи.
 *
 * <p><b>Зачем STOMP поверх него.</b> Голый WebSocket — это канал байтов без адресов.
 * STOMP добавляет к нему подписки на адреса ({@code SUBSCRIBE /topic/order.42.track}),
 * заголовки и кадры ошибок — и, главное, его понимает RabbitMQ.
 *
 * <p><b>Зачем RabbitMQ.</b> Подписки клиентов, подключённых к разным экземплярам сервиса,
 * должны видеть одни и те же точки. Встроенный брокер Spring держит их в памяти процесса,
 * поэтому в compose работает ретранслятор: сервис пересылает кадры в STOMP-плагин RabbitMQ,
 * и рассылку делает брокер. Выключенный ретранслятор ({@code app.tracking.live.relay.enabled})
 * возвращает встроенный брокер — для одного экземпляра и запуска из IDE.
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(LiveTrackingProperties.class)
@RequiredArgsConstructor
public class LiveTrackingConfig implements WebSocketMessageBrokerConfigurer {

    /** Точка подключения. Шлюз проксирует её как WebSocket — см. маршрут tracking-live. */
    public static final String ENDPOINT = "/ws/tracking";

    private final LiveTrackingProperties properties;
    private final StompAuthInterceptor stompAuthInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT)
                .setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        LiveTrackingProperties.Relay relay = properties.relay();

        // Кадры на /app/... идут в обработчики @MessageMapping (чат: «печатаю», «прочитал»),
        // на /topic/... — в брокер.
        registry.setApplicationDestinationPrefixes("/app");

        if (relay.enabled()) {
            // Два набора учётных данных: клиентские — для соединений браузеров, системные — для
            // соединения самого сервиса, через которое он публикует точки. У брокера из compose
            // это одна учётная запись.
            registry.enableStompBrokerRelay("/topic")
                    .setRelayHost(relay.host())
                    .setRelayPort(relay.port())
                    .setVirtualHost(relay.virtualHost())
                    .setClientLogin(relay.login())
                    .setClientPasscode(relay.passcode())
                    .setSystemLogin(relay.login())
                    .setSystemPasscode(relay.passcode());

            log.info("Живой трек: ретранслятор STOMP в {}:{}", relay.host(), relay.port());
        } else {
            registry.enableSimpleBroker("/topic");

            log.warn("Живой трек: встроенный брокер в памяти процесса. Подписки не переживут "
                    + "второго экземпляра сервиса — в compose включён ретранслятор в RabbitMQ");
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
    }
}
