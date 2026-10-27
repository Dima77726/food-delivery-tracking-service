package com.dima.fooddelivery.chat.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final ChatRateLimiter limiter = new ChatRateLimiter(3, Duration.ofSeconds(2), clock);

    @Test
    void shouldAllowBurstUpToCapacityThenAskToWait() {
        for (int i = 0; i < 3; i++) {
            assertEquals(0, limiter.tryConsume(1L, 10L));
        }

        long retryAfter = limiter.tryConsume(1L, 10L);

        assertEquals(2, retryAfter, "до следующего токена — один интервал пополнения");
    }

    @Test
    void shouldRefillOneTokenPerInterval() {
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(1L, 10L);
        }

        clock.advance(Duration.ofMillis(1999));
        assertTrue(limiter.tryConsume(1L, 10L) > 0, "токен ещё не накопился");

        clock.advance(Duration.ofMillis(1));
        assertEquals(0, limiter.tryConsume(1L, 10L));
        assertTrue(limiter.tryConsume(1L, 10L) > 0, "накопился ровно один токен");
    }

    @Test
    void shouldNotRefillAboveCapacity() {
        clock.advance(Duration.ofHours(1));

        for (int i = 0; i < 3; i++) {
            assertEquals(0, limiter.tryConsume(1L, 10L));
        }
        assertTrue(limiter.tryConsume(1L, 10L) > 0);
    }

    @Test
    void shouldKeepSeparateBucketsPerUserAndOrder() {
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(1L, 10L);
        }

        assertEquals(0, limiter.tryConsume(1L, 11L), "другой заказ — другое ведро");
        assertEquals(0, limiter.tryConsume(2L, 10L), "другой пользователь — другое ведро");
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-27T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
