package com.dima.fooddelivery.common.stores;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Соединение с Cassandra: голый драйвер DataStax вместо стартера Spring Boot.
 *
 * <p>В монолите Cassandra была одним из выключаемых хранилищ, и стартер не годился: он открывает
 * сессию на старте, и недоступный кластер ронял бы приложение, которому трек сегодня не нужен.
 * В сервисе трека Cassandra — его единственное хранилище и обязательна, но голый драйвер
 * остался: CQL здесь три запроса, и маппинг сущностей ради них не нужен.
 *
 * <p>Что теряется вместе со стартером: репозитории, маппинг сущностей, конвертеры. Взамен
 * CQL в {@code CourierTrackRepository} написан руками и виден целиком — ровно как SQL
 * в модуле audit. Для одной таблицы с тремя запросами это скорее плюс.
 *
 * <p><b>Порядок создания.</b> Сессию нельзя открыть на несуществующее пространство ключей,
 * а создать его можно только из сессии. Отсюда два шага: первая сессия открывается без
 * keyspace и выполняет CREATE KEYSPACE, вторая — уже с ним. Первая сразу закрывается,
 * долгоживущее соединение одно.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CassandraStoreProperties.class)
public class CassandraConfig {

    /**
     * Одна сессия на приложение. Драйвер держит внутри неё пул соединений ко всем узлам,
     * умеет находить нового координатора при падении и разбирает метаданные кластера —
     * создавать сессию на запрос было бы примерно так же разумно, как создавать на запрос
     * пул Hikari.
     *
     * <p>{@code destroyMethod = "close"} задан явно, хотя {@code CqlSession} и реализует
     * {@code AutoCloseable}: без закрытия сессии остаются рабочие потоки драйвера, и штатное
     * завершение приложения затягивается до таймаута.
     */
    @Bean(destroyMethod = "close")
    CqlSession cqlSession(CassandraStoreProperties properties) {
        String keyspace = properties.safeKeyspace();

        CqlSessionBuilder builder = CqlSession.builder()
                .addContactPoints(contactPoints(properties.contactPoints()))
                // Драйвер 4.x требует имя локального дата-центра явно и не угадывает его.
                // Это защита от случая, когда приложение молча начинает ходить за данными
                // через океан, потому что ближайший узел оказался недоступен.
                .withLocalDatacenter(properties.localDatacenter())
                .withConfigLoader(
                        DriverConfigLoader.programmaticBuilder()
                                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, properties.requestTimeout())
                                .build()
                );

        try (CqlSession bootstrap = builder.build()) {
            // SimpleStrategy и одна реплика — форма для одного узла в docker compose.
            // В кластере из нескольких дата-центров тут обязана быть NetworkTopologyStrategy:
            // SimpleStrategy раскладывает реплики по кольцу, не глядя на топологию,
            // и переживает потерю дата-центра ровно никак.
            bootstrap.execute(
                    "CREATE KEYSPACE IF NOT EXISTS " + keyspace
                            + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}"
            );

            log.info("Keyspace Cassandra готов: {}", keyspace);
        }

        return builder.withKeyspace(keyspace).build();
    }

    private static List<InetSocketAddress> contactPoints(List<String> raw) {
        return raw.stream().map(CassandraConfig::parse).toList();
    }

    /**
     * Адрес создаётся неразрешённым намеренно: имя узла резолвится драйвером в момент
     * подключения. Разрешить его здесь означало бы намертво запомнить IP на всё время жизни
     * приложения, а в docker compose и в Testcontainers адрес контейнера меняется.
     */
    private static InetSocketAddress parse(String hostAndPort) {
        int separator = hostAndPort.lastIndexOf(':');

        if (separator < 1 || separator == hostAndPort.length() - 1) {
            throw new IllegalStateException(
                    "Точка подключения Cassandra должна иметь вид host:port, получено: " + hostAndPort
            );
        }

        return InetSocketAddress.createUnresolved(
                hostAndPort.substring(0, separator),
                Integer.parseInt(hostAndPort.substring(separator + 1))
        );
    }
}
