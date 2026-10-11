package com.dima.fooddelivery.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * База для интеграционных тестов сервиса трека: настоящие Cassandra и RabbitMQ.
 *
 * <p>Транзакций здесь нет: Cassandra о них не знает, и откатывать нечего. Изоляцию тестов
 * дают уникальные идентификаторы доставок — см. {@link TestDataFactory}.
 *
 * <p>Порт случайный и настоящий: живой трек проверяется WebSocket-клиентом по сети.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.org.testcontainers=WARN",
                "logging.level.com.datastax.oss.driver=WARN"
        }
)
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestDataFactory testData;

    @Autowired
    protected TestJwt jwt;
}
