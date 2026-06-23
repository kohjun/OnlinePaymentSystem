package com.example.payment.infrastructure.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final MockAuthenticationFilter mockAuthenticationFilter;

    @Value("${app.security.external-auth.enabled:false}")
    private boolean externalAuthEnabled;

    @Value("${app.security.mock-auth.enabled:false}")
    private boolean mockAuthEnabled;

    @Value("${app.security.cors.enabled:true}")
    private boolean corsEnabled;

    @Value("${app.security.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    @Value("${app.security.cors.allowed-origin-patterns:}")
    private String corsAllowedOriginPatterns;

    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return new EverySaleJwtAuthenticationConverter();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = splitCsv(corsAllowedOrigins);
        List<String> patterns = splitCsv(corsAllowedOriginPatterns);

        if (origins.stream().anyMatch("*"::equals)) {
            patterns = new ArrayList<>(patterns);
            origins.stream().filter("*"::equals).forEach(patterns::add);
            origins = origins.stream().filter(origin -> !"*".equals(origin)).toList();
        }

        config.setAllowedOrigins(origins);
        config.setAllowedOriginPatterns(patterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Tenant-Id",
                "X-Partner-Id",
                "X-Correlation-Id",
                "Idempotency-Key",
                "X-EverySale-Customer-Id",
                "X-EverySale-Seller-Id",
                "X-EverySale-Roles",
                "X-Customer-Id",
                "X-Seller-Id",
                "X-Roles"
        ));
        config.setExposedHeaders(List.of("X-Tenant-Id", "X-Partner-Id", "X-Correlation-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/shared.html", "/*.css", "/*.js", "/*.png", "/*.ico", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/system/health", "/api/system/health/**", "/api/system/readiness", "/api/payments/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/marketplace/events/**", "/api/simulation/events/**", "/api/simulation/auction/status").permitAll()
                        .requestMatchers("/api/simulation/auth/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/system/dashboard/reset", "/api/system/inventory/reconcile").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/queue/clear").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/marketplace/events/*/raffle/draw", "/api/marketplace/events/*/auction/close").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/simulation/bid").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/simulation/status", "/api/simulation/orders").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/simulation/**").hasRole("ADMIN")
                        .requestMatchers("/api/system/dashboard/**", "/api/reservations/system/**").hasRole("ADMIN")
                        .requestMatchers("/api/sellers/moderation/**", "/api/sellers/*/payouts/*/release").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/payments/*/refund").hasRole("ADMIN")
                        .requestMatchers("/api/payments/toss/**", "/api/reservations/workflows/**", "/api/reservations/customer/**", "/api/system/customer/**", "/api/queue/**").authenticated()
                        .anyRequest().authenticated()
                );

        if (corsEnabled) {
            http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
        }
        if (mockAuthEnabled) {
            http.addFilterBefore(mockAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }
        if (externalAuthEnabled) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        }
        return http.build();
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }
}