package com.example.payment.infrastructure.config;

import com.example.payment.application.service.CompleteReservationIdempotencyService;
import com.example.payment.application.service.IdempotencyClaimWriter;
import com.example.payment.application.service.MarketplaceRealtimeEnvelope;
import com.example.payment.domain.entity.PaymentIdempotencyRecord;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.repository.PaymentIdempotencyRepository;
import com.example.payment.presentation.dto.response.AuctionBidResponse;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.RaffleEntryResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 저장되거나 다른 프로세스로 나가는 JSON이 Jackson 2 시절과 같은지 확인한다.
 *
 * src/test/resources/wire-format의 기준 파일은 {@link WireFormatSamples}를 Jackson 2
 * 매퍼(전환 직전의 spring-boot-jackson2 구성)로 쓴 결과다. 배포 중에는 옛 인스턴스와 새 인스턴스가 같은 DB 행,
 * Redis 키, Kafka 토픽을 함께 읽고 쓰므로 양쪽 방향을 모두 본다.
 * - 쓰기: 지금 매퍼가 같은 샘플을 기준 파일과 같은 JSON으로 쓴다.
 * - 읽기: 기준 파일(옛 인스턴스가 남긴 값)을 읽어 다시 쓰면 기준 파일과 같다.
 * 숫자 표기(150000.00)까지 비교하되 속성 순서는 보지 않는다. 읽는 쪽은 모두 이름으로
 * 읽고, 순서가 결과를 바꾸는 요청 지문은 해시 값을 그대로 비교한다.
 */
class WireFormatCompatibilityTest {

    private static final Path FIXTURES = Path.of("src/test/resources/wire-format");

    // 비교 전용. 숫자를 BigDecimal로 읽어 150000.00과 150000.0을 구별한다.
    private static final JsonMapper COMPARE = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Test
    void storedReservationResponsesKeepTheirFormat() {
        withMappers((app, redis) -> {
            assertWritesAndReads(app, "complete-reservation-response.json",
                    WireFormatSamples.completeReservationResponse(), CompleteReservationResponse.class);
            assertWritesAndReads(redis, "redis-complete-reservation-response.json",
                    WireFormatSamples.completeReservationResponse(), CompleteReservationResponse.class);
        });
    }

    @Test
    void cachedBidAndRaffleResponsesKeepTheirFormat() {
        withMappers((app, redis) -> {
            assertWritesAndReads(app, "auction-bid-response.json",
                    WireFormatSamples.auctionBidResponse(), AuctionBidResponse.class);
            assertWritesAndReads(app, "raffle-entry-response.json",
                    WireFormatSamples.raffleEntryResponse(), RaffleEntryResponse.class);
        });
    }

    @Test
    void kafkaPaymentEventsKeepTheirFormat() {
        withMappers((app, redis) -> {
            JsonMapper mapper = (JsonMapper) app;
            String written;
            try (JacksonJsonSerializer<PaymentEvent> serializer = new JacksonJsonSerializer<>(mapper)) {
                written = new String(serializer.serialize("payment-events", WireFormatSamples.paymentEvent()), StandardCharsets.UTF_8);
            }
            assertSameJson(fixture("payment-event.json"), written);

            PaymentEvent read;
            try (JacksonJsonDeserializer<PaymentEvent> deserializer = new JacksonJsonDeserializer<>(PaymentEvent.class, mapper, false)) {
                read = deserializer.deserialize("payment-events", fixture("payment-event.json").getBytes(StandardCharsets.UTF_8));
            }
            assertSameJson(fixture("payment-event.json"), app.writeValueAsString(read));
        });
    }

    @Test
    void outboxPayloadsKeepTheirFormat() {
        withMappers((app, redis) -> assertSameJson(fixture("outbox-envelope.json"),
                app.writeValueAsString(WireFormatSamples.outboxEnvelope())));
    }

    @Test
    void cachedDomainObjectsKeepTheirFormat() {
        withMappers((app, redis) -> {
            assertWritesAndReads(redis, "redis-order.json", WireFormatSamples.order(), Order.class);
            // elapsedMinutes는 지금 시각 기준 계산값이라 기록 시점마다 다르다.
            assertWritesAndReads(redis, "redis-payment.json", WireFormatSamples.payment(), Payment.class, "elapsedMinutes");
            assertWritesAndReads(redis, "redis-inventory-reservation.json",
                    WireFormatSamples.inventoryReservation(), InventoryReservation.class);
        });
    }

    @Test
    void redisTemplateValuesKeepTheirFormat() {
        withMappers((app, redis) -> {
            JacksonJsonRedisSerializer<Object> values = new JacksonJsonRedisSerializer<>(redis, Object.class);

            // CacheService는 Redis 매퍼로 만든 JSON 문자열을 다시 값 직렬화기로 감싼다.
            String cachedOrder = new String(values.serialize(redis.writeValueAsString(WireFormatSamples.order())), StandardCharsets.UTF_8);
            assertEquals(fixture("redis-cached-order-value.json"), cachedOrder);
            Object storedValue = values.deserialize(fixture("redis-cached-order-value.json").getBytes(StandardCharsets.UTF_8));
            assertSameJson(fixture("redis-order.json"), redis.writeValueAsString(redis.readValue(storedValue.toString(), Order.class)));

            String envelope = new String(values.serialize(WireFormatSamples.realtimeEnvelope()), StandardCharsets.UTF_8);
            assertSameJson(fixture("redis-realtime-envelope.json"), envelope);
            MarketplaceRealtimeEnvelope received = redis.readValue(fixture("redis-realtime-envelope.json"), MarketplaceRealtimeEnvelope.class);
            assertEquals("auction.bid", received.eventName());
            assertEquals(WireFormatSamples.realtimeEnvelope().occurredAt(), received.occurredAt());
        });
    }

    @Test
    void idempotencyRequestHashIsUnchanged() {
        // 해시가 바뀌면 배포 전에 받은 재시도가 IDEMPOTENCY_KEY_CONFLICT로 거절된다.
        withMappers((app, redis) -> {
            PaymentIdempotencyRepository repository = mock(PaymentIdempotencyRepository.class);
            IdempotencyClaimWriter claims = mock(IdempotencyClaimWriter.class);
            when(repository.findByTenantIdAndMerchantIdAndOperationAndIdempotencyKey(any(), any(), any(), any()))
                    .thenReturn(Optional.empty());

            new CompleteReservationIdempotencyService(repository, app, claims)
                    .prepare(WireFormatSamples.completeReservationRequest(), "wf-1");

            ArgumentCaptor<PaymentIdempotencyRecord> saved = ArgumentCaptor.forClass(PaymentIdempotencyRecord.class);
            verify(claims).insert(saved.capture());
            assertEquals(fixture("complete-reservation-request-hash.txt"), saved.getValue().getRequestHash());
        });
    }

    private static <T> void assertWritesAndReads(ObjectMapper mapper, String fixtureName, T sample, Class<T> type,
                                                 String... volatileFields) {
        String expected = fixture(fixtureName);
        assertSameJson(expected, mapper.writeValueAsString(sample), volatileFields);
        assertSameJson(expected, mapper.writeValueAsString(mapper.readValue(expected, type)), volatileFields);
    }

    private static void assertSameJson(String expected, String actual, String... volatileFields) {
        assertEquals(normalize(expected, volatileFields), normalize(actual, volatileFields));
    }

    private static String normalize(String json, String... volatileFields) {
        JsonNode tree = COMPARE.readTree(json);
        if (tree instanceof ObjectNode object) {
            for (String field : volatileFields) {
                object.remove(field);
            }
        }
        return sortedByName(tree).toString();
    }

    private static JsonNode sortedByName(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = COMPARE.createObjectNode();
            new TreeMap<>(node.propertyStream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .forEach((name, value) -> sorted.set(name, sortedByName(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = COMPARE.createArrayNode();
            node.values().forEach(value -> sorted.add(sortedByName(value)));
            return sorted;
        }
        return node;
    }

    private static String fixture(String name) {
        try {
            return Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void withMappers(BiConsumer<ObjectMapper, ObjectMapper> assertions) {
        ApplicationJsonMappers.contextRunner().run(context -> assertions.accept(
                context.getBean(ObjectMapper.class),
                context.getBean("redisObjectMapper", ObjectMapper.class)));
    }
}
