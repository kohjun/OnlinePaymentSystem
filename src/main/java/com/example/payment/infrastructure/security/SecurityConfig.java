package com.example.payment.infrastructure.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final MockAuthenticationFilter mockAuthenticationFilter;

    @Value("${app.security.external-auth.enabled:false}")
    private boolean externalAuthEnabled;

    @Value("${app.security.external-auth.audience:}")
    private String externalAuthAudience;

    @Value("${app.tenancy.bind-token-claims:false}")
    private boolean bindTokenClaims;

    @Value("${app.tenancy.require-token-tenant-claim:false}")
    private boolean requireTokenTenantClaim;

    @Value("${app.tenancy.require-token-partner-claim:false}")
    private boolean requireTokenPartnerClaim;

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
        return new EverySaleJwtAuthenticationConverter(firstCsvValue(externalAuthAudience));
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.external-auth.enabled", havingValue = "true")
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        Set<String> audiences = new LinkedHashSet<>(splitCsv(externalAuthAudience));
        if (audiences.isEmpty()) {
            throw new IllegalStateException("app.security.external-auth.audience is required when external auth is enabled.");
        }
        JwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
        if (!(decoder instanceof NimbusJwtDecoder nimbusJwtDecoder)) {
            throw new IllegalStateException("NimbusJwtDecoder is required for EverySale audience validation.");
        }
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
        nimbusJwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                new JwtAudienceValidator(audiences)
        ));
        return nimbusJwtDecoder;
    }

    @Bean
    JwtTenantAuthorizationFilter jwtTenantAuthorizationFilter() {
        return new JwtTenantAuthorizationFilter(
                bindTokenClaims,
                requireTokenTenantClaim,
                requireTokenPartnerClaim
        );
    }

    @Bean
    FilterRegistrationBean<JwtTenantAuthorizationFilter> jwtTenantAuthorizationFilterRegistration(
            JwtTenantAuthorizationFilter filter) {
        FilterRegistrationBean<JwtTenantAuthorizationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
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
                "X-EverySale-User-Id",
                "X-EverySale-Customer-Id",
                "X-EverySale-Seller-Id",
                "X-EverySale-Roles",
                "X-User-Id",
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
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtTenantAuthorizationFilter jwtTenantAuthorizationFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/shared.html", "/seller.html", "/app", "/app/**", "/*.css", "/*.js", "/*.png", "/*.ico", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/system/health", "/api/system/health/**", "/api/system/readiness", "/api/payments/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/payments/toss/webhooks/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/marketplace/events",
                                "/api/marketplace/events/page",
                                "/api/marketplace/events/*",
                                "/api/marketplace/events/*/raffle/status",
                                "/api/marketplace/events/*/raffle/stream",
                                "/api/marketplace/events/*/auction/status",
                                "/api/marketplace/events/*/auction/stream").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/system/inventory/reconcile").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/queue/clear").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/marketplace/events/*/raffle/draw", "/api/marketplace/events/*/auction/close").hasRole("ADMIN")
                        .requestMatchers("/api/reservations/system/**").hasRole("ADMIN")
                        .requestMatchers("/api/sellers/moderation/**", "/api/sellers/*/payouts/*/release").hasRole("ADMIN")
                        .requestMatchers("/api/payments/toss/**", "/api/reservations/workflows/**", "/api/reservations/customer/**", "/api/queue/**").authenticated()
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
            http.addFilterAfter(jwtTenantAuthorizationFilter, BearerTokenAuthenticationFilter.class);
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

    private String firstCsvValue(String value) {
        List<String> values = splitCsv(value);
        return values.isEmpty() ? null : values.get(0);
    }
}
