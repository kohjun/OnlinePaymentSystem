package com.example.payment.infrastructure.monitoring;

import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * 간단한 모니터링 서비스
 * - 캐시 작업 모니터링
 * - 결제 프로세스 성능 추적
 */
@Service
@Slf4j
public class MonitoringService {

    private final Map<String, AtomicLong> hitCounter = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> missCounter = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errorCounter = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> operationCounter = new ConcurrentHashMap<>();

    private final Map<String, Long> minLatency = new ConcurrentHashMap<>();
    private final Map<String, Long> maxLatency = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> totalLatency = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> latencyCount = new ConcurrentHashMap<>();

    /**
     * 캐시 작업 기록
     */
    public void recordCacheOperation(String key, CacheOperation operation, long durationNanos) {
        // 카운터 증가
        switch (operation) {
            case GET:
            case SET:
            case DELETE:
                incrementCounter(operationCounter, key);
                break;
            case HIT:
                incrementCounter(hitCounter, key);
                break;
            case MISS:
                incrementCounter(missCounter, key);
                break;
            case ERROR:
                incrementCounter(errorCounter, key);
                break;
        }

        // 성능 측정
        recordLatency(key, durationNanos);

        // 주기적으로 로그 출력 (샘플링)
        if (Math.random() < 0.01) { // 1% 확률로 로그 출력
            logMetrics(key);
        }
    }

    /**
     * 카운터 증가
     */
    private void incrementCounter(Map<String, AtomicLong> counter, String key) {
        counter.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 지연 시간 기록
     */
    private void recordLatency(String key, long durationNanos) {
        long durationMs = durationNanos / 1_000_000;

        // 최소값 업데이트
        minLatency.compute(key, (k, v) -> v == null ? durationMs : Math.min(v, durationMs));

        // 최대값 업데이트
        maxLatency.compute(key, (k, v) -> v == null ? durationMs : Math.max(v, durationMs));

        // 총계 및 카운트 업데이트 (평균 계산용)
        totalLatency.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(durationMs);
        latencyCount.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 메트릭 로그 출력
     */
    private void logMetrics(String key) {
        long hits = getCount(hitCounter, key);
        long misses = getCount(missCounter, key);
        long errors = getCount(errorCounter, key);
        long operations = getCount(operationCounter, key);

        long min = minLatency.getOrDefault(key, 0L);
        long max = maxLatency.getOrDefault(key, 0L);
        long total = totalLatency.getOrDefault(key, new AtomicLong(0)).get();
        long count = latencyCount.getOrDefault(key, new AtomicLong(0)).get();

        double avg = count > 0 ? (double) total / count : 0;

        log.debug("Cache metrics for key pattern '{}': operations={}, hits={}, misses={}, errors={}, " +
                        "latency=[min={}ms, avg={}ms, max={}ms]",
                key, operations, hits, misses, errors, min, String.format("%.2f", avg), max);
    }

    /**
     * 카운터 값 조회
     */
    private long getCount(Map<String, AtomicLong> counter, String key) {
        AtomicLong count = counter.get(key);
        return count != null ? count.get() : 0;
    }

    /**
     * 캐시 작업 유형
     */
    public enum CacheOperation {
        GET, SET, DELETE, HIT, MISS, ERROR
    }
}