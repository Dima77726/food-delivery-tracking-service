package com.dima.fooddelivery.tracking.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.dima.fooddelivery.common.stores.CassandraStoreProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Таблица трека курьера. DDL написан руками и выполняется на старте.
 *
 * <p>Liquibase сюда не дотягивается, и это нормально: пространство ключей приложения одно,
 * таблица одна, история миграций для неё пока не нужна. Как только таблиц станет несколько
 * и появится первая несовместимая правка, этот класс придётся заменить настоящим
 * инструментом миграций.
 *
 * <p><b>Первичный ключ — главное решение всей таблицы.</b> В CQL он состоит из двух частей.
 * Ключ партиции {@code delivery_id} определяет, на каком узле лежат данные: весь трек одной
 * доставки оказывается в одной партиции, на одном узле, и читается одним обращением.
 * Ключ кластеризации {@code recorded_at} задаёт порядок строк внутри партиции.
 *
 * <p>Из этого следуют и возможности, и ограничения. Запросить последние точки конкретной
 * доставки — дёшево. Запросить все точки всех курьеров за час — невозможно без полного
 * перебора кластера, и такого запроса в модуле нет. Это не недоделка, а плата за модель:
 * в Cassandra таблица проектируется под запрос, а не запрос под таблицу.
 *
 * <p><b>Ключ кластеризации — timeuuid, а не timestamp.</b> Разница здесь не стилистическая.
 * Тип timestamp в CQL хранит миллисекунды, а курьеров много и точки летят пачками: две
 * точки, отправленные в одну миллисекунду, получили бы одинаковый первичный ключ, и вторая
 * молча затёрла бы первую — вставка в Cassandra это upsert, ошибки не будет. Timeuuid
 * содержит время со стократно большей точностью плюс счётчик, поэтому он одновременно
 * уникален и упорядочен по времени. Это стандартный приём для временных рядов в Cassandra
 * ровно по этой причине.
 *
 * <p><b>CLUSTERING ORDER BY DESC</b> — не косметика. Единственное чтение модуля - последние
 * точки, и с обратным порядком хранения они лежат в начале партиции. С порядком по
 * возрастанию тот же запрос читал бы партицию с конца, что для Cassandra заметно дороже.
 *
 * <p><b>default_time_to_live</b> задан на уровне таблицы: строки исчезают сами через неделю.
 * Плановое удаление старых строк через DELETE в Cassandra — плохая идея: каждая удалённая
 * строка оставляет надгробие (tombstone), которое живёт до compaction и замедляет чтения.
 * TTL проставляется вместе со строкой и обходится дешевле.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierTrackSchema {

    public static final String TABLE = "courier_track";

    private final CqlSession session;
    private final CassandraStoreProperties properties;

    @PostConstruct
    void createTable() {
        session.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    delivery_id bigint,
                    recorded_at timeuuid,
                    courier_id  bigint,
                    latitude    double,
                    longitude   double,
                    speed_kmh   double,
                    PRIMARY KEY ((delivery_id), recorded_at)
                ) WITH CLUSTERING ORDER BY (recorded_at DESC)
                  AND default_time_to_live = %d
                """.formatted(TABLE, properties.trackTtlSeconds()));

        log.info(
                "Таблица Cassandra {}.{} готова, TTL={}",
                properties.safeKeyspace(),
                TABLE,
                properties.trackTtl()
        );
    }
}
