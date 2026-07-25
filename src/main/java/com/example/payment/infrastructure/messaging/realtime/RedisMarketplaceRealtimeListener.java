package com.example.payment.infrastructure.messaging.realtime;

import com.example.payment.application.service.MarketplaceRealtimeEnvelope;
import com.example.payment.application.service.MarketplaceRealtimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.marketplace.realtime.redis-broadcast-enabled", havingValue = "true")
public class RedisMarketplaceRealtimeListener implements MessageListener {

    private final MarketplaceRealtimeService realtimeService;

    @Qualifier("redisObjectMapper")
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            MarketplaceRealtimeEnvelope envelope = objectMapper.readValue(
                    message.getBody(),
                    MarketplaceRealtimeEnvelope.class
            );
            realtimeService.publishRemote(envelope);
        } catch (Exception e) {
            log.error("Failed to consume marketplace realtime event", e);
        }
    }
}
