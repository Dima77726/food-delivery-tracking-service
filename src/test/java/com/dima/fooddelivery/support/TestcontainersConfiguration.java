package com.dima.fooddelivery.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Cassandra и RabbitMQ со STOMP-плагином для интеграционных тестов сервиса трека.
 *
 * <p>Адреса передаются {@link DynamicPropertyRegistrar}: и Cassandra, и ретранслятор STOMP
 * настраиваются нашими свойствами, о которых автоконфигурация Spring Boot ничего не знает.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final int STOMP_PORT = 61613;

    /**
     * Cassandra стартует дольше всех остальных контейнеров. Куча урезана: по умолчанию узел
     * берёт четверть памяти машины, а тестам хватает пары сотен мегабайт.
     */
    @Bean
    CassandraContainer cassandraContainer() {
        return new CassandraContainer("cassandra:5.0")
                .withEnv("MAX_HEAP_SIZE", "256M")
                .withEnv("HEAP_NEWSIZE", "64M");
    }

    /** Тот же образ и тот же список плагинов, что в compose, — включая STOMP. */
    @Bean
    RabbitMQContainer rabbitContainer() {
        RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1.4-management")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("rabbitmq/enabled_plugins"),
                        "/etc/rabbitmq/enabled_plugins"
                );
        rabbit.addExposedPort(STOMP_PORT);

        return rabbit;
    }

    @Bean
    DynamicPropertyRegistrar storeProperties(CassandraContainer cassandra, RabbitMQContainer rabbit) {
        return registry -> {
            registry.add(
                    "app.stores.cassandra.contact-points",
                    () -> cassandra.getHost() + ":" + cassandra.getContactPoint().getPort()
            );
            registry.add("app.stores.cassandra.local-datacenter", cassandra::getLocalDatacenter);
            registry.add("app.stores.cassandra.keyspace", () -> "food_delivery_test");

            registry.add("app.tracking.live.relay.enabled", () -> "true");
            registry.add("app.tracking.live.relay.host", rabbit::getHost);
            registry.add("app.tracking.live.relay.port", () -> rabbit.getMappedPort(STOMP_PORT));
            registry.add("app.tracking.live.relay.login", rabbit::getAdminUsername);
            registry.add("app.tracking.live.relay.passcode", rabbit::getAdminPassword);

            // Приём точек по MQTT: AMQP-соединение к тому же брокеру. Сам флаг app.tracking.mqtt.enabled —
            // в свойствах AbstractIntegrationTest: условия бинов вычисляются раньше этих свойств.
            registry.add("spring.rabbitmq.host", rabbit::getHost);
            registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
            registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
            registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        };
    }

    @Bean
    @Primary
    TestOrders testOrders() {
        return new TestOrders();
    }

    @Bean
    TestDataFactory testDataFactory(TestOrders orders) {
        return new TestDataFactory(orders);
    }

    @Bean
    TestJwt testJwt() {
        return new TestJwt();
    }

    /** Декодер с открытым ключом {@link TestJwt} вместо JWKS сервиса пользователей. */
    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return TestJwt.decoder();
    }
}
