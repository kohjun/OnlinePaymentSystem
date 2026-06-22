package com.example.payment.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.queue.enabled", havingValue = "true")
public class QueueScheduler {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.queue.promote-size:50}")
    private int promoteSize;

    @Scheduled(fixedDelay = 1000)
    public void promoteQueueUsers() {
        try {
            // Check if queue has users
            Long queueSize = redisTemplate.opsForZSet().zCard("ticket_queue");
            if (queueSize != null && queueSize > 0) {
                // Pop the oldest N users from the queue
                Set<ZSetOperations.TypedTuple<Object>> usersToPromote = redisTemplate.opsForZSet().popMin("ticket_queue", promoteSize);
                if (usersToPromote != null && !usersToPromote.isEmpty()) {
                    log.info("Promoting {} users from standby queue to active status.", usersToPromote.size());
                    for (ZSetOperations.TypedTuple<Object> tuple : usersToPromote) {
                        if (tuple.getValue() != null) {
                            String customerId = tuple.getValue().toString();
                            String activeKey = "active_user:" + customerId;
                            // Give them active status for 60 seconds
                            redisTemplate.opsForValue().set(activeKey, "ACTIVE", 60, TimeUnit.SECONDS);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to promote users from standby queue", e);
        }
    }
}
