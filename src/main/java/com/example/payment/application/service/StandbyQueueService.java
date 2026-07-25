package com.example.payment.application.service;

import com.example.payment.infrastructure.tenancy.TenantContext;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class StandbyQueueService {

    private static final String TENANT_REGISTRY_KEY = "everysale:queue:tenants";
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final RedisTemplate<String, Object> redisTemplate;
    private final String instanceId = UUID.randomUUID().toString();

    @Value("${app.queue.enabled:false}")
    private boolean queueEnabled;

    @Value("${app.queue.promote-size:50}")
    private int promoteSize;

    @Value("${app.queue.max-active-users:50}")
    private int maxActiveUsers;

    @Value("${app.queue.active-lease-seconds:60}")
    private long activeLeaseSeconds;

    public StandbyQueueService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public QueueStatus join(String customerId) {
        String tenantId = currentTenant();
        if (!queueEnabled) {
            return QueueStatus.active(false, "Queue is disabled; request may proceed immediately.");
        }
        if (hasActiveLease(tenantId, customerId)) {
            renewActiveLease(tenantId, customerId);
            return QueueStatus.active(true, "Active queue token already exists.");
        }

        redisTemplate.opsForSet().add(TENANT_REGISTRY_KEY, tenantId);
        redisTemplate.opsForZSet().addIfAbsent(waitingKey(tenantId), customerId, System.currentTimeMillis());
        promoteTenant(tenantId);
        return status(tenantId, customerId);
    }

    public QueueStatus status(String customerId) {
        if (!queueEnabled) {
            return QueueStatus.active(false, "Queue is disabled; request may proceed immediately.");
        }
        return status(currentTenant(), customerId);
    }

    public boolean hasActiveLease(String customerId) {
        if (!queueEnabled) {
            return true;
        }
        String tenantId = currentTenant();
        boolean active = hasActiveLease(tenantId, customerId);
        if (active) {
            renewActiveLease(tenantId, customerId);
        }
        return active;
    }

    public void clear(String customerId) {
        String tenantId = currentTenant();
        redisTemplate.opsForZSet().remove(waitingKey(tenantId), customerId);
        redisTemplate.opsForZSet().remove(activeLeaseKey(tenantId), customerId);
        redisTemplate.delete(activeUserKey(tenantId, customerId));
    }

    public int promoteAllTenants() {
        Set<Object> tenantIds = redisTemplate.opsForSet().members(TENANT_REGISTRY_KEY);
        if (tenantIds == null || tenantIds.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        for (Object tenantId : tenantIds) {
            if (tenantId != null) {
                promoted += promoteTenant(tenantId.toString());
            }
        }
        return promoted;
    }

    int promoteTenant(String tenantId) {
        String lockKey = promotionLockKey(tenantId);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                instanceId,
                Duration.ofSeconds(5)
        );
        if (!Boolean.TRUE.equals(acquired)) {
            return 0;
        }

        try {
            cleanupExpiredLeases(tenantId);
            long activeCount = value(redisTemplate.opsForZSet().zCard(activeLeaseKey(tenantId)));
            long capacity = Math.max(0L, (long) maxActiveUsers - activeCount);
            long promotionCount = Math.min(Math.max(1, promoteSize), capacity);
            if (promotionCount <= 0) {
                return 0;
            }

            Set<ZSetOperations.TypedTuple<Object>> candidates = redisTemplate.opsForZSet()
                    .popMin(waitingKey(tenantId), promotionCount);
            if (candidates == null || candidates.isEmpty()) {
                return 0;
            }

            int promoted = 0;
            long expiresAt = System.currentTimeMillis() + Duration.ofSeconds(activeLeaseSeconds).toMillis();
            for (ZSetOperations.TypedTuple<Object> candidate : candidates) {
                if (candidate.getValue() == null) {
                    continue;
                }
                String customerId = candidate.getValue().toString();
                try {
                    redisTemplate.opsForValue().set(
                            activeUserKey(tenantId, customerId),
                            "ACTIVE",
                            Duration.ofSeconds(activeLeaseSeconds)
                    );
                    redisTemplate.opsForZSet().add(activeLeaseKey(tenantId), customerId, expiresAt);
                    promoted++;
                } catch (RuntimeException e) {
                    double originalScore = candidate.getScore() != null
                            ? candidate.getScore()
                            : System.currentTimeMillis();
                    redisTemplate.opsForZSet().add(waitingKey(tenantId), customerId, originalScore);
                    throw e;
                }
            }
            return promoted;
        } finally {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), instanceId);
        }
    }

    private QueueStatus status(String tenantId, String customerId) {
        if (hasActiveLease(tenantId, customerId)) {
            renewActiveLease(tenantId, customerId);
            return QueueStatus.active(true, "Active queue token exists.");
        }

        redisTemplate.opsForZSet().remove(activeLeaseKey(tenantId), customerId);
        Long zeroBasedRank = redisTemplate.opsForZSet().rank(waitingKey(tenantId), customerId);
        if (zeroBasedRank == null) {
            return QueueStatus.none(queueEnabled);
        }
        long rank = zeroBasedRank + 1;
        return QueueStatus.waiting(rank, calculateEstimatedTime(rank), queueEnabled);
    }

    private boolean hasActiveLease(String tenantId, String customerId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(activeUserKey(tenantId, customerId)));
    }

    private void renewActiveLease(String tenantId, String customerId) {
        Duration lease = Duration.ofSeconds(activeLeaseSeconds);
        redisTemplate.expire(activeUserKey(tenantId, customerId), lease);
        redisTemplate.opsForZSet().add(
                activeLeaseKey(tenantId),
                customerId,
                System.currentTimeMillis() + lease.toMillis()
        );
    }

    private void cleanupExpiredLeases(String tenantId) {
        String activeLeaseKey = activeLeaseKey(tenantId);
        Set<Object> expiredCustomers = redisTemplate.opsForZSet().rangeByScore(
                activeLeaseKey,
                0,
                System.currentTimeMillis()
        );
        if (expiredCustomers == null || expiredCustomers.isEmpty()) {
            return;
        }
        for (Object customerId : expiredCustomers) {
            if (customerId != null) {
                redisTemplate.delete(activeUserKey(tenantId, customerId.toString()));
            }
        }
        redisTemplate.opsForZSet().removeRangeByScore(activeLeaseKey, 0, System.currentTimeMillis());
    }

    private long calculateEstimatedTime(long rank) {
        long batchSize = Math.max(1, promoteSize);
        return Math.max(1L, (rank + batchSize - 1L) / batchSize);
    }

    private long value(Long value) {
        return value != null ? value : 0L;
    }

    private String currentTenant() {
        String tenantId = TenantContext.getTenantId();
        return tenantId == null || tenantId.isBlank()
                ? "default"
                : tenantId.trim().toLowerCase(Locale.ROOT);
    }

    private String waitingKey(String tenantId) {
        return "everysale:queue:" + tenantId + ":waiting";
    }

    private String activeLeaseKey(String tenantId) {
        return "everysale:queue:" + tenantId + ":active";
    }

    private String activeUserKey(String tenantId, String customerId) {
        return "everysale:queue:" + tenantId + ":active:" + customerId;
    }

    private String promotionLockKey(String tenantId) {
        return "everysale:queue:" + tenantId + ":promotion-lock";
    }

    @Builder
    public record QueueStatus(
            String status,
            long rank,
            long waitingTime,
            boolean queueEnabled,
            String message
    ) {
        static QueueStatus active(boolean queueEnabled, String message) {
            return new QueueStatus("ACTIVE", 0, 0, queueEnabled, message);
        }

        static QueueStatus waiting(long rank, long waitingTime, boolean queueEnabled) {
            return new QueueStatus("WAITING", rank, waitingTime, queueEnabled, "Waiting for an active queue lease.");
        }

        static QueueStatus none(boolean queueEnabled) {
            return new QueueStatus("NONE", -1, 0, queueEnabled, "Customer is not in the standby queue.");
        }
    }
}
