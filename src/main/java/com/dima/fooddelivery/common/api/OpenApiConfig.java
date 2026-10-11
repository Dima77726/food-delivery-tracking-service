package com.dima.fooddelivery.common.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Описание API для Swagger UI.
 *
 * <p>Схема безопасности объявлена здесь один раз, а контроллеры ссылаются на неё
 * по имени через {@code @SecurityRequirement(name = "bearer-jwt")}. Благодаря этому
 * в Swagger UI появляется кнопка Authorize, и защищённые эндпоинты можно дёргать
 * прямо из браузера, вставив токен из {@code /api/v1/auth/login}.
 */
@Configuration
public class OpenApiConfig {

    static final String SECURITY_SCHEME_NAME = "bearer-jwt";

    @Bean
    OpenAPI foodDeliveryOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Food Delivery — Tracking Service API")
                        .version("v1")
                        .description("""
                                Сервис трека курьера: координаты по REST и в реальном времени.

                                Курьер отправляет точки POST .../positions, клиент читает трек
                                GET .../track или подписывается по WebSocket (STOMP) на
                                /topic/order.{orderId}.track — точки приходят сами, без опроса.
                                """))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
