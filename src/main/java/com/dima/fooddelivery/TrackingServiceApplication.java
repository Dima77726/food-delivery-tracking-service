package com.dima.fooddelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Сервис трека курьера: точки в Cassandra и живой трек по WebSocket.
 *
 * <p>Класс в корневом пакете по той же причине, что у остальных вынесенных сервисов: модуль
 * {@code tracking} переехал из монолита со своими пакетами.
 */
@SpringBootApplication
public class TrackingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackingServiceApplication.class, args);
    }
}
