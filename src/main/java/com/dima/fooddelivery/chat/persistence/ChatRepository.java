package com.dima.fooddelivery.chat.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.dima.fooddelivery.chat.domain.ChatMessage;
import com.dima.fooddelivery.chat.domain.ChatRole;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Чат заказа в Cassandra. Запросы подготовленные, как у трека курьера.
 *
 * <p>{@link ChatSchema} в конструкторе — чтобы таблицы существовали до подготовки запросов.
 */
@Repository
public class ChatRepository {

    private final CqlSession session;
    private final PreparedStatement claimClientMessageId;
    private final PreparedStatement insertMessage;
    private final PreparedStatement selectMessage;
    private final PreparedStatement selectLatest;
    private final PreparedStatement selectBefore;
    private final PreparedStatement upsertRead;
    private final PreparedStatement selectReads;

    public ChatRepository(CqlSession session, ChatSchema schema) {
        this.session = session;

        this.claimClientMessageId = session.prepare("""
                INSERT INTO %s (order_id, client_message_id, message_id) VALUES (?, ?, ?) IF NOT EXISTS
                """.formatted(ChatSchema.DEDUP));
        this.insertMessage = session.prepare("""
                INSERT INTO %s (order_id, message_id, sender_id, sender_role, body, client_message_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """.formatted(ChatSchema.MESSAGES));
        this.selectMessage = session.prepare("""
                SELECT order_id, message_id, sender_id, sender_role, body, client_message_id
                FROM %s WHERE order_id = ? AND message_id = ?
                """.formatted(ChatSchema.MESSAGES));
        // Порядок от новых к старым задан в CLUSTERING ORDER — ORDER BY не нужен.
        this.selectLatest = session.prepare("""
                SELECT order_id, message_id, sender_id, sender_role, body, client_message_id
                FROM %s WHERE order_id = ? LIMIT ?
                """.formatted(ChatSchema.MESSAGES));
        // Курсор — timeuuid последнего сообщения страницы. Сравнение timeuuid в CQL — по времени.
        this.selectBefore = session.prepare("""
                SELECT order_id, message_id, sender_id, sender_role, body, client_message_id
                FROM %s WHERE order_id = ? AND message_id < ? LIMIT ?
                """.formatted(ChatSchema.MESSAGES));
        this.upsertRead = session.prepare("""
                INSERT INTO %s (order_id, user_id, last_read) VALUES (?, ?, ?)
                """.formatted(ChatSchema.READS));
        this.selectReads = session.prepare("""
                SELECT user_id, last_read FROM %s WHERE order_id = ?
                """.formatted(ChatSchema.READS));
    }

    /**
     * Сохраняет сообщение ровно один раз на {@code clientMessageId}.
     *
     * <p>Сначала лёгкая транзакция «занять» идентификатор клиента, потом само сообщение. Если
     * идентификатор уже занят — это повтор: возвращается то сообщение, которое уже записано.
     * Если повтор застал запись недоделанной (сервис упал между двумя вставками), сообщение
     * дописывается с тем идентификатором, что уже занят, — повтор чинит, а не дублирует.
     */
    public Saved save(Long orderId, Long senderId, ChatRole role, String text, UUID clientMessageId) {
        UUID messageId = Uuids.timeBased();

        ResultSet claim = session.execute(claimClientMessageId.bind(orderId, clientMessageId, messageId));
        boolean first = claim.wasApplied();

        if (!first) {
            // Неприменённая лёгкая транзакция возвращает строку, которая ей помешала.
            UUID existingId = claim.one().getUuid("message_id");
            Optional<ChatMessage> existing = findOne(orderId, existingId);
            if (existing.isPresent()) {
                return new Saved(existing.get(), true);
            }
            messageId = existingId;
        }

        session.execute(insertMessage.bind(orderId, messageId, senderId, role.name(), text, clientMessageId));

        return new Saved(new ChatMessage(orderId, messageId, senderId, role, text, toInstant(messageId), clientMessageId), !first);
    }

    public Optional<ChatMessage> findOne(Long orderId, UUID messageId) {
        Row row = session.execute(selectMessage.bind(orderId, messageId)).one();
        return Optional.ofNullable(row).map(ChatRepository::toMessage);
    }

    /** Страница переписки от новых к старым; {@code before} — курсор или {@code null} для начала. */
    public List<ChatMessage> findPage(Long orderId, UUID before, int limit) {
        ResultSet rows = before == null
                ? session.execute(selectLatest.bind(orderId, limit))
                : session.execute(selectBefore.bind(orderId, before, limit));

        List<ChatMessage> messages = new ArrayList<>();
        for (Row row : rows) {
            messages.add(toMessage(row));
        }
        return messages;
    }

    /**
     * Отметка «прочитано». Это upsert: из двух одновременных отметок одного пользователя
     * останется последняя записанная, даже если она старше. Для «прочитано» это безвредно —
     * следующая отметка всё исправит, — а лёгкая транзакция на каждое прочтение была бы дорогой.
     */
    public void markRead(Long orderId, Long userId, UUID messageId) {
        session.execute(upsertRead.bind(orderId, userId, messageId));
    }

    public Map<Long, UUID> findReads(Long orderId) {
        Map<Long, UUID> reads = new HashMap<>();
        for (Row row : session.execute(selectReads.bind(orderId))) {
            reads.put(row.getLong("user_id"), row.getUuid("last_read"));
        }
        return reads;
    }

    private static ChatMessage toMessage(Row row) {
        UUID messageId = row.getUuid("message_id");
        return new ChatMessage(
                row.getLong("order_id"),
                messageId,
                row.getLong("sender_id"),
                ChatRole.valueOf(row.getString("sender_role")),
                row.getString("body"),
                toInstant(messageId),
                row.getUuid("client_message_id")
        );
    }

    private static Instant toInstant(UUID timeBasedUuid) {
        return Instant.ofEpochMilli(Uuids.unixTimestamp(timeBasedUuid));
    }

    /**
     * @param message   сохранённое сообщение
     * @param duplicate это повтор уже принятой отправки — рассылать заново не нужно
     */
    public record Saved(ChatMessage message, boolean duplicate) {
    }
}
