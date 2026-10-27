package com.dima.fooddelivery.chat.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Таблицы чата заказа в Cassandra. Как и у трека, DDL выполняется на старте.
 *
 * <p>Три таблицы под три запроса — в Cassandra таблица проектируется под запрос:
 * <ul>
 *     <li>{@value #MESSAGES} — переписка заказа страницами от новых к старым. Партиция — заказ:
 *     весь чат лежит на одном узле и читается одним обращением. Чат короткий (одна доставка),
 *     поэтому партиция не разрастётся;</li>
 *     <li>{@value #DEDUP} — «этот clientMessageId уже принят». Вставка в неё — лёгкая
 *     транзакция ({@code IF NOT EXISTS}), и только она решает, первая это отправка или повтор;</li>
 *     <li>{@value #READS} — до какого сообщения дочитал каждый участник.</li>
 * </ul>
 *
 * <p><b>Почему дедупликация отдельной таблицей.</b> Обычная вставка в Cassandra — upsert: повтор
 * с тем же ключом молча перезаписал бы строку. Но ключ сообщения — timeuuid сервера, и у повтора
 * он другой, поэтому повтор стал бы вторым сообщением. Лёгкая транзакция (Paxos между репликами)
 * дорогая — в несколько раз медленнее обычной записи, — поэтому она только здесь, на короткой
 * строке со сроком жизни сутки, а не на каждом чтении.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSchema {

    public static final String MESSAGES = "order_chat_message";
    public static final String DEDUP = "order_chat_dedup";
    public static final String READS = "order_chat_read";

    /** Переписка живёт месяц: дольше незачем, а разбор жалобы за это время успеет состояться. */
    static final Duration MESSAGE_TTL = Duration.ofDays(30);

    /** Повтор отправки приходит в пределах секунд; сутки — с огромным запасом. */
    static final Duration DEDUP_TTL = Duration.ofDays(1);

    private final CqlSession session;

    @PostConstruct
    void createTables() {
        session.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    order_id          bigint,
                    message_id        timeuuid,
                    sender_id         bigint,
                    sender_role       text,
                    body              text,
                    client_message_id uuid,
                    PRIMARY KEY ((order_id), message_id)
                ) WITH CLUSTERING ORDER BY (message_id DESC)
                  AND default_time_to_live = %d
                """.formatted(MESSAGES, MESSAGE_TTL.toSeconds()));

        session.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    order_id          bigint,
                    client_message_id uuid,
                    message_id        timeuuid,
                    PRIMARY KEY ((order_id), client_message_id)
                ) WITH default_time_to_live = %d
                """.formatted(DEDUP, DEDUP_TTL.toSeconds()));

        session.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    order_id   bigint,
                    user_id    bigint,
                    last_read  timeuuid,
                    PRIMARY KEY ((order_id), user_id)
                ) WITH default_time_to_live = %d
                """.formatted(READS, MESSAGE_TTL.toSeconds()));

        log.info("Таблицы чата готовы: {}, {}, {}", MESSAGES, DEDUP, READS);
    }
}
