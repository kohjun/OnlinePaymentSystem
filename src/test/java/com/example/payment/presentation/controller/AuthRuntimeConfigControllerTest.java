package com.example.payment.presentation.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthRuntimeConfigControllerTest {

    @Test
    void localModeDoesNotExposeExternalProviderSettings() {
        var response = new AuthRuntimeConfigController(new MockEnvironment()).config().getBody();

        assertEquals("LOCAL", response.mode());
        assertNull(response.authorizationUri());
        assertNull(response.tokenUri());
        assertNull(response.clientId());
    }

    @Test
    void externalModeReturnsOnlyPublicPkceClientConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.security.external-auth.enabled", "true")
                .withProperty("app.security.external-auth.frontend.authorization-uri", "https://id.example.com/authorize")
                .withProperty("app.security.external-auth.frontend.token-uri", "https://id.example.com/token")
                .withProperty("app.security.external-auth.frontend.client-id", "everysale-web")
                .withProperty("app.security.external-auth.frontend.scopes", "openid,profile,email");

        var response = new AuthRuntimeConfigController(environment).config().getBody();

        assertEquals("OIDC", response.mode());
        assertEquals("https://id.example.com/authorize", response.authorizationUri());
        assertEquals("everysale-web", response.clientId());
        assertEquals(java.util.List.of("openid", "profile", "email"), response.scopes());
    }
}
