package com.example.payment.infrastructure.messaging.realtime;

import com.example.payment.application.service.MarketplaceRealtimeEnvelope;
import com.example.payment.application.service.MarketplaceRealtimePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.marketplace.realtime.redis-broadcast-enabled", havingValue = "true")
public class RedisMarketplaceRealtimePublisher implements MarketplaceRealtimePublisher {

    public static final String CHANNEL = "everysale:marketplace:realtime";

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void publish(MarketplaceRealtimeEnvelope envelope) {
        try {
            redisTemplate.convertAndSend(CHANNEL, envelope);
        } catch (RuntimeException e) {
            log.error("Failed to publish marketplace realtime event: streamType={}, eventId={}, eventName={}",
                    envelope.streamType(), envelope.eventId(), envelope.eventName(), e);
            throw e;
        }
    }
}
