package com.example.payment.infrastructure.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtcTimeZoneInitializerTest {

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(original);
    }

    @Test
    void pinsKoreanHostToUtcSoLocalAndExplicitUtcClocksAgree() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));

        new UtcTimeZoneInitializer().onApplicationEvent(null);

        assertEquals(ZoneOffset.UTC.normalized(), TimeZone.getDefault().toZoneId().normalized());
        Duration drift = Duration.between(LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now()).abs();
        assertTrue(drift.compareTo(Duration.ofSeconds(5)) < 0, "local clock drift from UTC: " + drift);
    }

    @Test
    void isRegisteredForEverySpringApplicationStartup() {
        // main 외에 SpringBootTest와 테스트 하위 프로세스도 이 등록을 거쳐 기동한다.
        boolean registered = SpringFactoriesLoader.forDefaultResourceLocation()
                .load(ApplicationListener.class)
                .stream()
                .anyMatch(UtcTimeZoneInitializer.class::isInstance);

        assertTrue(registered, "UtcTimeZoneInitializer must stay registered in META-INF/spring.factories");
    }
}
