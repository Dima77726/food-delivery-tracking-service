package com.dima.fooddelivery.tracking.mqtt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTTP-бэкенд аутентификации RabbitMQ ({@code rabbitmq_auth_backend_http}) для курьеров на MQTT.
 *
 * <p>Брокер не знает пользователей платформы. Учётку на каждого курьера в RabbitMQ не заведёшь, а
 * общий логин на всех не скажет, кто есть кто. Поэтому брокер спрашивает приложение — четырьмя
 * POST-запросами с формой, ответ — текст {@code allow} или {@code deny}:
 * <ul>
 *     <li>{@code /user} — можно ли войти: логин — идентификатор курьера, пароль — его access-токен;
 *     токен проверяется тем же декодером JWKS, что и REST, и его {@code sub} обязан совпасть с логином;</li>
 *     <li>{@code /vhost} — только «/»;</li>
 *     <li>{@code /resource} — запись в {@code amq.topic} и служебные очереди сессии MQTT;</li>
 *     <li>{@code /topic} — главное: писать можно только в {@code couriers.<свой id>.*}. Подменить
 *     чужой идентификатор в топике нельзя — это сторожит брокер ещё до приложения.</li>
 * </ul>
 *
 * <p>Сервисные учётки (сам tracking-service, notification-service) проверяет встроенный бэкенд
 * брокера — он стоит первым ({@code auth_backends.1 = internal}), а сюда приходят только
 * неизвестные ему логины.
 *
 * <p><b>Сеть, а не токен.</b> Ручки открыты без аутентификации: их зовёт брокер, у которого нет
 * токена. Защищает то, что шлюз путь {@code /internal/**} не ведёт никуда, а порт сервиса в compose
 * не опубликован — запрос придёт только изнутри сети. Решение брокер кэширует на время соединения;
 * соединение живёт дольше 15-минутного токена — это осознанный компромисс (см. учебник, глава 4).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/internal/rabbitmq", produces = MediaType.TEXT_PLAIN_VALUE)
@ConditionalOnProperty(name = "app.tracking.mqtt.enabled", havingValue = "true")
public class RabbitHttpAuthController {

    static final String ALLOW = "allow";
    static final String DENY = "deny";

    private final JwtDecoder jwtDecoder;

    @PostMapping("/user")
    public String user(@RequestParam String username, @RequestParam(required = false) String password) {
        if (password == null || password.isBlank()) {
            return DENY;
        }
        try {
            Jwt jwt = jwtDecoder.decode(password);
            List<String> roles = jwt.getClaimAsStringList("roles");
            boolean courier = roles != null && roles.contains("COURIER");
            if (courier && username.equals(jwt.getSubject())) {
                return ALLOW;
            }
            log.warn("MQTT: вход отклонён: username={}, sub={}, courier={}", username, jwt.getSubject(), courier);
        } catch (JwtException exception) {
            log.warn("MQTT: вход отклонён, токен недействителен: username={}, reason={}", username, exception.getMessage());
        }
        return DENY;
    }

    @PostMapping("/vhost")
    public String vhost(@RequestParam String vhost) {
        return "/".equals(vhost) ? ALLOW : DENY;
    }

    /**
     * Обмен {@code amq.topic} — для публикации; очереди {@code mqtt-subscription-*} плагин создаёт
     * под сессию клиента. Остальное курьеру ни к чему.
     */
    @PostMapping("/resource")
    public String resource(@RequestParam Map<String, String> params) {
        String resource = params.get("resource");
        String name = params.getOrDefault("name", "");
        if ("exchange".equals(resource) && "amq.topic".equals(name)) {
            return ALLOW;
        }
        if ("queue".equals(resource) && name.startsWith("mqtt-subscription-")) {
            return ALLOW;
        }
        return DENY;
    }

    /** Писать — только в свои топики; читать (подписываться) курьеру нечего. */
    @PostMapping("/topic")
    public String topic(@RequestParam Map<String, String> params) {
        String username = params.getOrDefault("username", "");
        String permission = params.getOrDefault("permission", "");
        String routingKey = params.getOrDefault("routing_key", "");

        if ("write".equals(permission) && routingKey.startsWith("couriers." + username + ".")) {
            return ALLOW;
        }
        log.warn("MQTT: запрещено {} в {} для {}", permission, routingKey, username);
        return DENY;
    }
}
