package com.dima.fooddelivery.common.stores;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Быстрый тест: проверка имени keyspace и значений по умолчанию.
 *
 * <p>Проверка имени существует не ради аккуратности. В CQL у DDL нет параметров, поэтому имя
 * пространства ключей попадает в запрос конкатенацией — и строка из конфигурации становится
 * ровно тем, чем в SQL является незапараметризованный ввод. Тест держит эту проверку на месте.
 */
class CassandraStorePropertiesTest {

    @Test
    void shouldAcceptPlainKeyspaceName() {
        assertEquals("food_delivery", properties("food_delivery").safeKeyspace());
    }

    @Test
    void shouldRejectKeyspaceWithStatementSeparator() {
        assertThrows(
                IllegalStateException.class,
                () -> properties("food_delivery; DROP KEYSPACE system_auth").safeKeyspace()
        );
    }

    @Test
    void shouldRejectKeyspaceStartingWithDigit() {
        assertThrows(IllegalStateException.class, () -> properties("1food").safeKeyspace());
    }

    @Test
    void shouldRejectEmptyKeyspace() {
        assertThrows(IllegalStateException.class, () -> properties("").safeKeyspace());
    }

    /**
     * Значения по умолчанию подставляет компактный конструктор. Без них запись
     * сервис без единой строки {@code app.stores.cassandra.*} в конфигурации
     * давала бы null в каждом поле и падение с NullPointerException на старте.
     */
    @Test
    void shouldFallBackToDefaultsWhenNothingConfigured() {
        CassandraStoreProperties defaults =
                new CassandraStoreProperties(null, null, null, null, null);

        assertAll(
                () -> assertEquals(List.of("localhost:9042"), defaults.contactPoints()),
                () -> assertEquals("datacenter1", defaults.localDatacenter()),
                () -> assertEquals("food_delivery", defaults.keyspace()),
                () -> assertEquals(Duration.ofDays(7), defaults.trackTtl()),
                () -> assertEquals(604_800, defaults.trackTtlSeconds())
        );
    }

    private static CassandraStoreProperties properties(String keyspace) {
        return new CassandraStoreProperties(
                List.of("localhost:9042"),
                "datacenter1",
                keyspace,
                Duration.ofDays(7),
                Duration.ofSeconds(5)
        );
    }
}
