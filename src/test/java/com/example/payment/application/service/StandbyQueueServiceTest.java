package com.example.payment.application.service;

import com.example.payment.infrastructure.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StandbyQueueServiceTest {

    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
    private final ZSetOperations<String, Object> zSetOperations = mock(ZSetOperations.class);
    private final SetOperations<String, Object> setOperations = mock(SetOperations.class);
    private final StandbyQueueService service = new StandbyQueueService(redisTemplate);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        ReflectionTestUtils.setField(service, "queueEnabled", true);
        ReflectionTestUtils.setField(service, "promoteSize", 50);
        ReflectionTestUtils.setField(service, "maxActiveUsers", 50);
        ReflectionTestUtils.setField(service, "activeLeaseSeconds", 60L);
        TenantContext.set("TENANT-A", "PARTNER-A", "COR-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void firstWaitingCustomerIsPromotedImmediatelyWhenCapacityExists() {
        String activeUserKey = "everysale:queue:tenant-a:active:CUST-1";
        when(redisTemplate.hasKey(activeUserKey)).thenReturn(false, true);
        when(zSetOperations.addIfAbsent(eq("everysale:queue:tenant-a:waiting"), eq("CUST-1"), anyDouble()))
                .thenReturn(true);
        when(valueOperations.setIfAbsent(
                eq("everysale:queue:tenant-a:promotion-lock"), any(), eq(Duration.ofSeconds(5))))
                .thenReturn(true);
        when(zSetOperations.rangeByScore(eq("everysale:queue:tenant-a:active"), anyDouble(), anyDouble()))
                .thenReturn(Collections.emptySet());
        when(zSetOperations.zCard("everysale:queue:tenant-a:active")).thenReturn(0L);
        when(zSetOperations.popMin("everysale:queue:tenant-a:waiting", 50L))
                .thenReturn(Set.of(new DefaultTypedTuple<>("CUST-1", 1000D)));

        StandbyQueueService.QueueStatus result = service.join("CUST-1");

        assertEquals("ACTIVE", result.status());
        assertEquals(0, result.rank());
        verify(valueOperations).set(eq(activeUserKey), eq("ACTIVE"), eq(Duration.ofSeconds(60)));
        verify(zSetOperations, times(2)).add(eq("everysale:queue:tenant-a:active"), eq("CUST-1"), anyDouble());
        verify(redisTemplate).expire(activeUserKey, Duration.ofSeconds(60));
    }

    @Test
    void repeatedJoinKeepsOriginalQueuePosition() {
        when(redisTemplate.hasKey("everysale:queue:tenant-a:active:CUST-2")).thenReturn(false);
        when(zSetOperations.addIfAbsent(eq("everysale:queue:tenant-a:waiting"), eq("CUST-2"), anyDouble()))
                .thenReturn(false);
        when(valueOperations.setIfAbsent(
                eq("everysale:queue:tenant-a:promotion-lock"), any(), eq(Duration.ofSeconds(5))))
                .thenReturn(false);
        when(zSetOperations.rank("everysale:queue:tenant-a:waiting", "CUST-2")).thenReturn(4L);

        StandbyQueueService.QueueStatus result = service.join("CUST-2");

        assertEquals("WAITING", result.status());
        assertEquals(5, result.rank());
        assertEquals(1, result.waitingTime());
        verify(zSetOperations, never()).add(eq("everysale:queue:tenant-a:waiting"), eq("CUST-2"), anyDouble());
    }

    @Test
    void failedLeaseCreationRestoresCustomerToOriginalQueueScore() {
        when(valueOperations.setIfAbsent(
                eq("everysale:queue:tenant-a:promotion-lock"), any(), eq(Duration.ofSeconds(5))))
                .thenReturn(true);
        when(zSetOperations.rangeByScore(eq("everysale:queue:tenant-a:active"), anyDouble(), anyDouble()))
                .thenReturn(Collections.emptySet());
        when(zSetOperations.zCard("everysale:queue:tenant-a:active")).thenReturn(0L);
        when(zSetOperations.popMin("everysale:queue:tenant-a:waiting", 50L))
                .thenReturn(Set.of(new DefaultTypedTuple<>("CUST-3", 1234D)));
        doThrow(new IllegalStateException("Redis write failed"))
                .when(valueOperations).set(
                        eq("everysale:queue:tenant-a:active:CUST-3"),
                        eq("ACTIVE"),
                        eq(Duration.ofSeconds(60))
                );

        assertThrows(IllegalStateException.class, () -> service.promoteTenant("tenant-a"));

        verify(zSetOperations).add("everysale:queue:tenant-a:waiting", "CUST-3", 1234D);
    }
}
