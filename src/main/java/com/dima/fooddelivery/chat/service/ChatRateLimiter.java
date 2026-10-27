package com.dima.fooddelivery.chat.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ограничение частоты сообщений: ведро токенов (token bucket) на пару «пользователь, заказ».
 *
 * <p>В ведре до {@code capacity} токенов, каждое сообщение забирает один, и ведро пополняется
 * равномерно — по токену за {@code refillEvery}. Так можно быстро отправить несколько сообщений
 * подряд (ёмкость ведра — допустимый всплеск), но не держать высокий темп долго.
 *
 * <p>Ведро живёт в памяти экземпляра. С несколькими экземплярами пользователь, попадающий
 * на разные, получит лимит на каждом — для защиты чата от залипшей кнопки этого достаточно.
 * Общий лимит на все экземпляры живёт в шлюзе (тот же алгоритм, но в Redis, атомарно Lua-скриптом).
 *
 * <p>Ведра не вытесняются: запись — два числа на пару «пользователь, заказ», писавшую в чат
 * с момента старта экземпляра; тысячи заказов в день — это килобайты. Станет больше —
 * карту заменит кэш с вытеснением по времени простоя (Caffeine, {@code expireAfterAccess}).
 */
@Component
public class ChatRateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final long refillNanos;
    private final Clock clock;

    @Autowired
    public ChatRateLimiter(
            @Value("${app.chat.rate-limit.capacity:5}") int capacity,
            @Value("${app.chat.rate-limit.refill-every:PT2S}") Duration refillEvery
    ) {
        this(capacity, refillEvery, Clock.systemUTC());
    }

    ChatRateLimiter(int capacity, Duration refillEvery, Clock clock) {
        this.capacity = capacity;
        this.refillNanos = refillEvery.toNanos();
        this.clock = clock;
    }

    /**
     * @return 0, если сообщение разрешено, иначе — через сколько секунд появится токен
     */
    public long tryConsume(Long userId, Long orderId) {
        long now = clock.instant().toEpochMilli() * 1_000_000L;
        long[] retryAfter = {0};

        buckets.compute(userId + ":" + orderId, (key, bucket) -> {
            Bucket current = bucket == null ? new Bucket(capacity, now) : bucket.refill(now, capacity, refillNanos);
            if (current.tokens >= 1) {
                return new Bucket(current.tokens - 1, current.updatedAt);
            }
            long nanosToToken = refillNanos - (now - current.updatedAt);
            retryAfter[0] = Math.max(1, (nanosToToken + 999_999_999L) / 1_000_000_000L);
            return current;
        });

        return retryAfter[0];
    }

    private record Bucket(double tokens, long updatedAt) {

        Bucket refill(long now, int capacity, long refillNanos) {
            long elapsed = now - updatedAt;
            long whole = elapsed / refillNanos;
            if (whole == 0) {
                return this;
            }
            double refilled = Math.min(capacity, tokens + whole);
            // Остаток времени, не дотянувший до целого токена, не теряется.
            return new Bucket(refilled, refilled >= capacity ? now : updatedAt + whole * refillNanos);
        }
    }
}
