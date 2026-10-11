package com.dima.fooddelivery.tracking.live;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Настройки живого трека.
 *
 * @param allowedOrigins откуда браузеру разрешено открывать WebSocket. Проверка Origin —
 *                       единственная защита WebSocket от чужих страниц: cookie здесь не
 *                       участвуют, но список всё равно должен быть явным, а не «*»
 * @param relay          ретранслятор в брокер. Выключен — подписки держит встроенный брокер
 *                       в памяти процесса: годится для одного экземпляра и для запуска из IDE
 */
@ConfigurationProperties(prefix = "app.tracking.live")
public record LiveTrackingProperties(List<String> allowedOrigins, Relay relay) {

    public LiveTrackingProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        relay = relay == null ? new Relay(false, "localhost", 61613, "/", null, null) : relay;
    }

    /**
     * @param enabled     ходить ли в RabbitMQ через его STOMP-плагин
     * @param virtualHost виртуальный хост RabbitMQ; у брокера из compose он один — «/»
     */
    public record Relay(boolean enabled, String host, int port, String virtualHost, String login, String passcode) {
    }
}
