package com.dima.fooddelivery.tracking.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.dima.fooddelivery.tracking.domain.CourierPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Трек курьера в Cassandra. CQL написан руками, запросы подготовленные.
 *
 * <p>Ближайший родственник в проекте — {@code AuditLogRepository} на голом JDBC: там тоже нет
 * ни маппинга, ни репозитория из библиотеки, а строки разбираются вручную. Разница в языке
 * запросов и в том, чего этим языком нельзя выразить: ни JOIN, ни ORDER BY по произвольной
 * колонке, ни WHERE по неключевому полю здесь не существует в принципе.
 *
 * <p><b>Почему подготовленные запросы.</b> Cassandra кэширует разобранный запрос по узлам
 * кластера, поэтому подготовка один раз на приложение экономит разбор на каждой вставке —
 * а вставок здесь поток. Второе, не менее важное: подготовленный запрос с параметрами
 * исключает склейку CQL из строк, то есть и инъекцию.
 *
 * <p>Готовятся они в конструкторе, а не при первом обращении: подготовка ходит в кластер,
 * и делать это внутри обработки первого же запроса пользователя незачем.
 *
 * <p>{@link CourierTrackSchema} стоит в параметрах конструктора не потому, что нужен, а чтобы
 * задать порядок: Spring создаст и проинициализирует схему прежде, чем сюда дойдёт очередь.
 * Подготовить запрос к несуществующей таблице нельзя.
 */
@Slf4j
@Repository
public class CourierTrackRepository {

    private final CqlSession session;
    private final PreparedStatement insertPosition;
    private final PreparedStatement selectLastPositions;

    public CourierTrackRepository(CqlSession session, CourierTrackSchema schema) {
        this.session = session;

        // TTL не указан в запросе намеренно: он задан на уровне таблицы в CourierTrackSchema.
        // Одно место вместо двух, и срок жизни строк меняется правкой DDL, а не поиском
        // по всем местам, откуда идёт вставка.
        this.insertPosition = session.prepare("""
                INSERT INTO %s (delivery_id, recorded_at, courier_id, latitude, longitude, speed_kmh)
                VALUES (?, ?, ?, ?, ?, ?)
                """.formatted(CourierTrackSchema.TABLE));

        // ORDER BY здесь не нужен: порядок задан в CLUSTERING ORDER таблицы, и строки уже
        // лежат на диске от свежих к старым. LIMIT в Cassandra ограничивает именно чтение,
        // а не отбрасывает лишнее после него.
        this.selectLastPositions = session.prepare("""
                SELECT delivery_id, recorded_at, courier_id, latitude, longitude, speed_kmh
                FROM %s
                WHERE delivery_id = ?
                LIMIT ?
                """.formatted(CourierTrackSchema.TABLE));
    }

    /**
     * Записывает точку и возвращает её вместе с проставленным временем.
     *
     * <p>Идентификатор строки — timeuuid, и генерирует его репозиторий, а не вызывающий код.
     * {@link Uuids#timeBased()} гарантирует и упорядоченность по времени, и уникальность
     * даже для точек, отправленных в одну и ту же миллисекунду. Обычный {@code UUID.randomUUID}
     * здесь не подошёл бы: он уникален, но не упорядочен, а от ключа кластеризации требуется
     * ровно порядок.
     *
     * <p>Проверки на дубликат нет и быть не может: вставка в Cassandra — это upsert, строка
     * с тем же ключом просто перезаписалась бы. Уникальность ключа и есть то, что делает
     * эту особенность безопасной.
     */
    public CourierPosition save(
            Long deliveryId,
            Long courierId,
            double latitude,
            double longitude,
            Double speedKmh
    ) {
        UUID recordedAt = Uuids.timeBased();

        session.execute(insertPosition.bind(
                deliveryId,
                recordedAt,
                courierId,
                latitude,
                longitude,
                speedKmh
        ));

        return new CourierPosition(
                deliveryId,
                courierId,
                toInstant(recordedAt),
                latitude,
                longitude,
                speedKmh
        );
    }

    /** Последние точки доставки, свежие первыми. */
    public List<CourierPosition> findLastPositions(Long deliveryId, int limit) {
        List<CourierPosition> positions = new ArrayList<>();

        for (Row row : session.execute(selectLastPositions.bind(deliveryId, limit))) {
            positions.add(toPosition(row));
        }

        return positions;
    }

    /**
     * Разбор строки руками. Скорость читается через {@code Object}, потому что колонка
     * допускает null, а {@code getDouble} на null молча вернул бы 0.0 — то есть
     * "курьер стоит" вместо "телефон не сообщил скорость".
     */
    private static CourierPosition toPosition(Row row) {
        Object speed = row.getObject("speed_kmh");

        return new CourierPosition(
                row.getLong("delivery_id"),
                row.getLong("courier_id"),
                toInstant(row.getUuid("recorded_at")),
                row.getDouble("latitude"),
                row.getDouble("longitude"),
                speed == null ? null : ((Number) speed).doubleValue()
        );
    }

    /**
     * Время из timeuuid. Наружу отдаётся обычный момент времени: то, что внутри он служит
     * ещё и ключом сортировки, — деталь хранения, и API о ней знать незачем.
     */
    private static Instant toInstant(UUID timeBasedUuid) {
        return Instant.ofEpochMilli(Uuids.unixTimestamp(timeBasedUuid));
    }
}
