package com.dima.fooddelivery.common.security;

import com.dima.fooddelivery.common.web.UserIdMdcFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Конфигурация Spring Security.
 *
 * <p>Монолит больше не выпускает токены — это делает identity-service, подписывая их закрытым
 * RSA-ключом. Здесь токены только проверяются: открытый ключ скачивается из JWKS сервиса
 * пользователей и кэшируется. Секрета, которым можно было бы подписать поддельный токен,
 * у сервиса нет вовсе — в этом и смысл RS256.
 *
 * <p>JWKS скачивается лениво, при первом токене, а не на старте: сервис поднимается, даже
 * если сервис пользователей ещё не готов. Незнакомый {@code kid} в токене заставляет
 * перечитать JWKS — смена ключа не требует перезапуска.
 *
 * <p>Сессий нет: {@code STATELESS}. Отсюда и выключенный CSRF — cookie для аутентификации
 * не используются.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    /** Claim и префикс ролей — общий контракт токена, тот же, что у сервиса пользователей. */
    static final String ROLES_CLAIM = "roles";
    static final String ROLE_PREFIX = "ROLE_";
    static final String ADMIN_ROLE = "ADMIN";

    /**
     * Документация и рукопожатие WebSocket. Рукопожатие открыто намеренно: браузер не умеет
     * добавить к нему заголовок с токеном, поэтому токен проверяется на кадре STOMP CONNECT —
     * см. {@code StompAuthInterceptor}.
     */
    private static final String[] PUBLIC_GET_PATHS = {
            "/ws/tracking",
            "/ws/tracking/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final JwtProperties jwtProperties;
    private final CurrentUser currentUser;

    /**
     * Цепочка для служебных эндпоинтов.
     *
     * <p>{@code @Order(1)} ставит её перед основной: иначе запросы к actuator дошли бы
     * до {@code anyRequest().authenticated()} и Prometheus получал бы 401.
     *
     * <p>Доступ открыт, и это безопасно ровно потому, что actuator слушает отдельный порт,
     * который в docker-compose не публикуется наружу. Защита здесь сетевая, а не прикладная —
     * у скрейпера всё равно нет способа обновлять JWT.
     */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN_ROLE)
                        .anyRequest().authenticated()
                )
                // httpBasic намеренно не включён: единственный способ аутентификации — токен.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                // Строго после разбора токена: до него контекст безопасности пуст.
                .addFilterAfter(new UserIdMdcFilter(currentUser), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Проверка подписи по JWKS сервиса пользователей и издателя.
     *
     * <p>Алгоритм задан явно: декодер примет только RS256. Иначе токен, подписанный
     * по другой схеме, мог бы пройти проверку там, где её автор на это не рассчитывал.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwtProperties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));

        return decoder;
    }

    /**
     * Переводит claim {@code roles} в authorities Spring Security.
     *
     * <p>По умолчанию конвертер ищет claim {@code scope} и вешает префикс {@code SCOPE_}.
     * Нам нужен префикс {@code ROLE_}, иначе {@code hasRole('ADMIN')} не сработает —
     * и это молчаливый отказ в доступе, который тяжело искать.
     */
    /**
     * Тот же разбор токена, что и для HTTP, — для кадров STOMP. Одна функция на оба входа:
     * роли из claim {@code roles} обязаны значить одно и то же в REST и в WebSocket.
     */
    public static AbstractAuthenticationToken authentication(Jwt jwt) {
        return jwtAuthenticationConverter().convert(jwt);
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName(ROLES_CLAIM);
        authoritiesConverter.setAuthorityPrefix(ROLE_PREFIX);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        return converter;
    }
}
