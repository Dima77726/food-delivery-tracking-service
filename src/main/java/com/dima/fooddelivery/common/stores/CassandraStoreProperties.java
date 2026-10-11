package com.dima.fooddelivery.common.stores;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Настройки подключения к Cassandra.
 *
 * <p>Свои, а не {@code spring.cassandra.*} из Spring Boot: стартер Cassandra в проект
 * не подключён, и разбирать его свойства некому. Причины отказа от стартера описаны
 * в {@link CassandraConfig}.
 *
 * @param contactPoints   узлы кластера в виде host:port
 * @param localDatacenter имя локального дата-центра; драйвер 4.x требует его явно
 * @param keyspace        пространство ключей приложения
 * @param trackTtl        сколько хранить точки трека
 * @param requestTimeout  таймаут одного запроса к кластеру
 */
@ConfigurationProperties(prefix = "app.stores.cassandra")
public record CassandraStoreProperties(
        List<String> contactPoints,
        String localDatacenter,
        String keyspace,
        Duration trackTtl,
        Duration requestTimeout
) {

    public CassandraStoreProperties {
        contactPoints = contactPoints == null || contactPoints.isEmpty()
                ? List.of("localhost:9042")
                : contactPoints;
        localDatacenter = localDatacenter == null ? "datacenter1" : localDatacenter;
        keyspace = keyspace == null ? "food_delivery" : keyspace;
        trackTtl = trackTtl == null ? Duration.ofDays(7) : trackTtl;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
    }

    /**
     * Имя пространства ключей подставляется в CQL конкатенацией: параметров у DDL в CQL нет.
     * Значит, проверять его форму обязаны мы сами, иначе строка из конфигурации становится
     * дырой, через которую в кластер уезжает произвольная команда.
     */
    public String safeKeyspace() {
        if (!keyspace.matches("[a-zA-Z][a-zA-Z0-9_]{0,47}")) {
            throw new IllegalStateException(
                    "Недопустимое имя keyspace: " + keyspace
                            + ". Разрешены буква в начале, затем буквы, цифры и подчёркивания"
            );
        }

        return keyspace;
    }

    public int trackTtlSeconds() {
        return Math.toIntExact(trackTtl.toSeconds());
    }
}
