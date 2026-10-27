package com.example.payment.infrastructure.config;

import com.example.payment.infrastructure.persistence.redis.config.RedisConfig;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.MarketplaceEventResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class JacksonConfigTest {

    // 운영과 같은 조합: Boot 자동 구성 + 이 설정 + RedisConfig가 등록하는 Redis 전용 매퍼.
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class)
            .withBean("redisObjectMapper", ObjectMapper.class, () -> new RedisConfig().redisObjectMapper());

    @Test
    void primaryMapperIsBootBuiltAndSeparateFromRedisMapper() {
        withMappers((mappers) -> assertNotSame(mappers.application(), mappers.redis()));
    }

    @Test
    void errorResponsesExposeCodeAtTopLevelForTheFrontend() {
        withMappers((mappers) -> {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "rejected");
            problem.setProperty("code", "MARKETPLACE_REQUEST_REJECTED");

            JsonNode json = mappers.application().valueToTree(problem);

            assertEquals("MARKETPLACE_REQUEST_REJECTED", json.path("code").asText());
            assertFalse(json.has("properties"));
        });
    }

    @Test
    void applicationMapperWritesLocalDateTimeAsUtcInstant() {
        withMappers((mappers) -> assertEquals("{\"endsAt\":\"2026-10-25T01:30:00Z\"}",
                write(mappers.application(), Map.of("endsAt", LocalDateTime.of(2026, 10, 25, 1, 30)))));
    }

    @Test
    void responseFieldsWithJsonFormatAlsoCarryUtcMarker() {
        withMappers((mappers) -> {
            MarketplaceEventResponse event = MarketplaceEventResponse.builder()
                    .endsAt(LocalDateTime.of(2026, 10, 25, 1, 30, 15, 900_000_000))
                    .build();

            assertEquals("2026-10-25T01:30:15Z", mappers.application().valueToTree(event).get("endsAt").asText());
        });
    }

    @Test
    void previouslyStoredResponseBodiesWithoutUtcMarkerStillRead() {
        withMappers((mappers) -> {
            String stored = "{\"status\":\"SUCCESS\",\"payment\":{\"processedAt\":\"2026-10-20T09:15:00\"}}";
            String current = "{\"status\":\"SUCCESS\",\"payment\":{\"processedAt\":\"2026-10-20T09:15:00Z\"}}";

            assertEquals(LocalDateTime.of(2026, 10, 20, 9, 15),
                    read(mappers.application(), stored).getPayment().getProcessedAt());
            assertEquals(LocalDateTime.of(2026, 10, 20, 9, 15),
                    read(mappers.application(), current).getPayment().getProcessedAt());
        });
    }

    @Test
    void redisStorageFormatIsUnchanged() {
        withMappers((mappers) -> assertEquals("{\"at\":\"2026-10-25T01:30:00\"}",
                write(mappers.redis(), Map.of("at", LocalDateTime.of(2026, 10, 25, 1, 30)))));
    }

    private void withMappers(Consumer<Mappers> assertions) {
        context.run(ctx -> assertions.accept(new Mappers(
                ctx.getBean(ObjectMapper.class),
                ctx.getBean("redisObjectMapper", ObjectMapper.class))));
    }

    private static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static CompleteReservationResponse read(ObjectMapper mapper, String json) {
        try {
            return mapper.readValue(json, CompleteReservationResponse.class);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private record Mappers(ObjectMapper application, ObjectMapper redis) {
    }
}
