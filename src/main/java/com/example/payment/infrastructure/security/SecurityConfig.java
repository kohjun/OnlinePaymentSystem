package com.example.payment.infrastructure.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/shared.html", "/*.png", "/*.ico", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/system/health", "/api/system/health/**", "/api/system/readiness", "/api/payments/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/marketplace/events/**", "/api/simulation/events/**", "/api/simulation/auction/status").permitAll()
                        .requestMatchers("/api/simulation/auth/**", "/api/queue/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/system/dashboard/reset", "/api/system/inventory/reconcile").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/simulation/**").hasRole("ADMIN")
                        .requestMatchers("/api/system/dashboard/**", "/api/reservations/system/**").hasRole("ADMIN")
                        .requestMatchers("/api/sellers/moderation/**", "/api/sellers/*/payouts/*/release").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/payments/*/refund").hasRole("ADMIN")
                        .requestMatchers("/api/payments/toss/**", "/api/reservations/workflows/**", "/api/reservations/customer/**", "/api/system/customer/**").authenticated()
                        .anyRequest().permitAll()
                );

        if (mockAuthEnabled) {
            http.addFilterBefore(mockAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }
        if (externalAuthEnabled) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        }
        return http.build();
    }
}
