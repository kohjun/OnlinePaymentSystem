package com.example.payment.application.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckoutRedirectPolicyTest {

    private final CheckoutRedirectPolicy policy =
            new CheckoutRedirectPolicy("https://app.example.com, http://localhost:8080/, *");

    @ParameterizedTest
    @ValueSource(strings = {
            "/app/?tossResult=success",
            "https://app.example.com/app/?tossResult=fail",
            "https://app.example.com:443/app/",
            "HTTPS://APP.EXAMPLE.COM/app/",
            "http://localhost:8080/app/?tossResult=success"
    })
    void allowsSameSitePathsAndConfiguredOrigins(String url) {
        assertEquals(url, policy.requireAllowed(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://attacker.example/collect",
            "//attacker.example/app/",
            "/\\attacker.example/app/",
            "https://app.example.com@attacker.example/app/",
            "https://app.example.com.attacker.example/app/",
            "http://app.example.com/app/",
            "https://app.example.com:8443/app/",
            "javascript:alert(1)",
            "app/?tossResult=success",
            "/app/?tossResult=success\nSet-Cookie: x",
            " "
    })
    void rejectsEverythingElseIncludingWildcardConfiguration(String url) {
        MarketplaceCheckoutException rejected =
                assertThrows(MarketplaceCheckoutException.class, () -> policy.requireAllowed(url));

        assertEquals("INVALID_REDIRECT_URL", rejected.getCode());
    }
}
