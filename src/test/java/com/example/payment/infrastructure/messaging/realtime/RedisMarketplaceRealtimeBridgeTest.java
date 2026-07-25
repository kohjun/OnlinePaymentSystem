package com.example.payment.infrastructure.messaging.realtime;

import com.example.payment.application.service.MarketplaceRealtimeEnvelope;
import com.example.payment.application.service.MarketplaceRealtimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.RedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisMarketplaceRealtimeBridgeTest {

    @Test
    void publisherUsesDedicatedMarketplaceChannel() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        RedisMarketplaceRealtimePublisher publisher = new RedisMarketplaceRealtimePublisher(redisTemplate);
        MarketplaceRealtimeEnvelope envelope = envelope();

        publisher.publish(envelope);

        verify(redisTemplate).convertAndSend(RedisMarketplaceRealtimePublisher.CHANNEL, envelope);
    }

    @Test
    void listenerDeserializesEnvelopeAndForwardsToRealtimeService() throws Exception {
        MarketplaceRealtimeService realtimeService = mock(MarketplaceRealtimeService.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RedisMarketplaceRealtimeListener listener = new RedisMarketplaceRealtimeListener(realtimeService, objectMapper);
        MarketplaceRealtimeEnvelope envelope = envelope();
        byte[] body = objectMapper.writeValueAsBytes(envelope);

        listener.onMessage(new DefaultMessage(
                RedisMarketplaceRealtimePublisher.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body
        ), null);

        verify(realtimeService).publishRemote(org.mockito.ArgumentMatchers.any(MarketplaceRealtimeEnvelope.class));
    }

    private MarketplaceRealtimeEnvelope envelope() {
        return new MarketplaceRealtimeEnvelope(
                "INSTANCE-A",
                "AUCTION",
                "EVT-1",
                "bid-placed",
                Map.of("highestBid", 10000),
                Instant.parse("2026-07-10T00:00:00Z")
        );
    }
}
