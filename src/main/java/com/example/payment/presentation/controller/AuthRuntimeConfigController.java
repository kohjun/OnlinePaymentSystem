package com.example.payment.presentation.controller;

import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthRuntimeConfigController {

    private final Environment environment;

    public AuthRuntimeConfigController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/config")
    public ResponseEntity<AuthRuntimeConfig> config() {
        boolean external = environment.getProperty(
                "app.security.external-auth.enabled", Boolean.class, false);
        if (!external) {
            return ResponseEntity.ok(new AuthRuntimeConfig("LOCAL", null, null, null, List.of()));
        }
        return ResponseEntity.ok(new AuthRuntimeConfig(
                "OIDC",
                value("app.security.external-auth.frontend.authorization-uri"),
                value("app.security.external-auth.frontend.token-uri"),
                value("app.security.external-auth.frontend.client-id"),
                csv("app.security.external-auth.frontend.scopes", "openid,profile,email")
        ));
    }

    private String value(String key) {
        String value = environment.getProperty(key, "").trim();
        return value.isEmpty() ? null : value;
    }

    private List<String> csv(String key, String defaultValue) {
        return Arrays.stream(environment.getProperty(key, defaultValue).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    public record AuthRuntimeConfig(
            String mode,
            String authorizationUri,
            String tokenUri,
            String clientId,
            List<String> scopes
    ) {
    }
}
