package com.example.payment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 시스템 모니터링 서비스
 * - API 요청 처리량 및 성능 지표 수집
 * - 응답 시간 분포 트래킹
 * - 에러율 모니터링
 */
@Service
@Slf4j
public class MonitoringService {

    // 엔드포인트별 메트릭
    private final Map<String, EndpointMetrics> endpointMetrics = new ConcurrentHashMap<>();

    // 전체 시스템 메트릭
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger successfulRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);

    // 마지막 집계 시간
    private Instant lastResetTime = Instant.now();

    // 주기적 리포팅을 위한 스케줄러
    private final ScheduledExecutorService scheduler;

    public MonitoringService() {
        this.scheduler = Executors.newScheduledThreadPool(1);

        // 주요 엔드포인트 미리 등록
        registerEndpoint("/api/payment/process");
        registerEndpoint("/api/payment/{paymentId}");
        registerEndpoint("/api/orders");
        registerEndpoint("/api/orders/{orderId}");

        // 주기적 메트릭 리포팅 시작
        startPeriodicReporting();
    }

    /**
     * 새 엔드포인트 등록
     */
    public void registerEndpoint(String endpoint) {
        endpointMetrics.putIfAbsent(endpoint, new EndpointMetrics());
    }

    /**
     * 요청 시작 기록
     */
    public void recordRequestStart(String endpoint) {
        // 전체 활성 요청 증가
        activeRequests.incrementAndGet();
        totalRequests.incrementAndGet();

        // 엔드포인트별 활성 요청 증가
        EndpointMetrics metrics = endpointMetrics.computeIfAbsent(endpoint, k -> new EndpointMetrics());
        metrics.getActiveRequests().incrementAndGet();
        metrics.getTotalRequests().incrementAndGet();

        // 현재 시간 기록
        metrics.getRequestStartTimes().put(Thread.currentThread().getId(), System.nanoTime());
    }

    /**
     * 요청 완료 기록
     */
    public void recordRequestEnd(String endpoint, boolean success) {
        long threadId = Thread.currentThread().getId();

        // 전체 활성 요청 감소
        activeRequests.decrementAndGet();

        if (success) {
            successfulRequests.incrementAndGet();
        } else {
            failedRequests.incrementAndGet();
        }

        // 엔드포인트별 메트릭 업데이트
        EndpointMetrics metrics = endpointMetrics.get(endpoint);
        if (metrics != null) {
            metrics.getActiveRequests().decrementAndGet();

            if (success) {
                metrics.getSuccessfulRequests().incrementAndGet();
            } else {
                metrics.getFailedRequests().incrementAndGet();
            }

            // 응답 시간 계산
            Long startTime = metrics.getRequestStartTimes().remove(threadId);
            if (startTime != null) {
                long duration = System.nanoTime() - startTime;
                updateResponseTimeMetrics(metrics, duration);
            }
        }
    }

    /**
     * 응답 시간 메트릭 업데이트
     */
    private void updateResponseTimeMetrics(EndpointMetrics metrics, long durationNanos) {
        double durationMs = durationNanos / 1_000_000.0;

        // 응답 시간 히스토그램 업데이트
        if (durationMs < 10) {
            metrics.getResponseTime0to10ms().incrementAndGet();
        } else if (durationMs < 50) {
            metrics.getResponseTime10to50ms().incrementAndGet();
        } else if (durationMs < 100) {
            metrics.getResponseTime50to100ms().incrementAndGet();
        } else if (durationMs < 500) {
            metrics.getResponseTime100to500ms().incrementAndGet();
        } else if (durationMs < 1000) {
            metrics.getResponseTime500to1000ms().incrementAndGet();
        } else {
            metrics.getResponseTimeOver1000ms().incrementAndGet();
        }

        // 최소/최대/평균 응답 시간 업데이트
        synchronized (metrics) {
            // 최소값 업데이트
            if (metrics.getMinResponseTimeMs() == 0 || durationMs < metrics.getMinResponseTimeMs()) {
                metrics.setMinResponseTimeMs(durationMs);
            }

            // 최대값 업데이트
            if (durationMs > metrics.getMaxResponseTimeMs()) {
                metrics.setMaxResponseTimeMs(durationMs);
            }

            // 평균값 업데이트
            long count = metrics.getSuccessfulRequests().get() + metrics.getFailedRequests().get();
            double newAvg = ((metrics.getAvgResponseTimeMs() * (count - 1)) + durationMs) / count;
            metrics.setAvgResponseTimeMs(newAvg);
        }
    }

    /**
     * 주기적 메트릭 리포팅 설정
     */
    private void startPeriodicReporting() {
        // 1분마다 메트릭 리포트
        scheduler.scheduleAtFixedRate(this::reportMetrics, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 메트릭 리포트 생성 및 출력
     */
    @Scheduled(fixedRate = 60000) // 1분마다
    public void reportMetrics() {
        Instant now = Instant.now();
        Duration elapsed = Duration.between(lastResetTime, now);
        double minutes = elapsed.toMillis() / 60000.0;

        log.info("=== System Metrics Report ===");
        log.info("Time period: {} minutes", String.format("%.2f", minutes));
        log.info("Total requests: {}", totalRequests.get());
        log.info("Active requests: {}", activeRequests.get());
        log.info("Successful requests: {}", successfulRequests.get());
        log.info("Failed requests: {}", failedRequests.get());
        log.info("Success rate: {}%",
                totalRequests.get() > 0
                        ? String.format("%.2f", (successfulRequests.get() * 100.0) / totalRequests.get())
                        : "N/A");
        log.info("Throughput: {} req/sec",
                String.format("%.2f", totalRequests.get() / (elapsed.toMillis() / 1000.0)));
        log.info("");

        // 엔드포인트별 메트릭 리포트
        log.info("=== Endpoint Metrics ===");
        endpointMetrics.forEach((endpoint, metrics) -> {
            if (metrics.getTotalRequests().get() > 0) {
                log.info("Endpoint: {}", endpoint);
                log.info("  Requests: {} (active: {})",
                        metrics.getTotalRequests().get(),
                        metrics.getActiveRequests().get());
                log.info("  Success/Failure: {}/{}",
                        metrics.getSuccessfulRequests().get(),
                        metrics.getFailedRequests().get());
                log.info("  Response time: avg={}ms, min={}ms, max={}ms",
                        String.format("%.2f", metrics.getAvgResponseTimeMs()),
                        String.format("%.2f", metrics.getMinResponseTimeMs()),
                        String.format("%.2f", metrics.getMaxResponseTimeMs()));
                log.info("  Response time distribution:");
                log.info("    0-10ms: {}", metrics.getResponseTime0to10ms().get());
                log.info("    10-50ms: {}", metrics.getResponseTime10to50ms().get());
                log.info("    50-100ms: {}", metrics.getResponseTime50to100ms().get());
                log.info("    100-500ms: {}", metrics.getResponseTime100to500ms().get());
                log.info("    500-1000ms: {}", metrics.getResponseTime500to1000ms().get());
                log.info("    >1000ms: {}", metrics.getResponseTimeOver1000ms().get());
                log.info("");
            }
        });

        // 필요에 따라 메트릭 리셋
        // resetMetrics();
    }

    /**
     * 메트릭 초기화
     */
    public void resetMetrics() {
        lastResetTime = Instant.now();
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);

        endpointMetrics.forEach((endpoint, metrics) -> {
            metrics.getTotalRequests().set(0);
            metrics.getSuccessfulRequests().set(0);
            metrics.getFailedRequests().set(0);
            metrics.getResponseTime0to10ms().set(0);
            metrics.getResponseTime10to50ms().set(0);
            metrics.getResponseTime50to100ms().set(0);
            metrics.getResponseTime100to500ms().set(0);
            metrics.getResponseTime500to1000ms().set(0);
            metrics.getResponseTimeOver1000ms().set(0);
            metrics.setMinResponseTimeMs(0);
            metrics.setMaxResponseTimeMs(0);
            metrics.setAvgResponseTimeMs(0);
        });
    }

    /**
     * 엔드포인트별 메트릭 데이터 구조
     */
    @Data
    public static class EndpointMetrics {
        // 요청 카운터
        private final AtomicInteger activeRequests = new AtomicInteger(0);
        private final AtomicInteger totalRequests = new AtomicInteger(0);
        private final AtomicInteger successfulRequests = new AtomicInteger(0);
        private final AtomicInteger failedRequests = new AtomicInteger(0);

        // 응답 시간 히스토그램
        private final AtomicLong responseTime0to10ms = new AtomicLong(0);
        private final AtomicLong responseTime10to50ms = new AtomicLong(0);
        private final AtomicLong responseTime50to100ms = new AtomicLong(0);
        private final AtomicLong responseTime100to500ms = new AtomicLong(0);
        private final AtomicLong responseTime500to1000ms = new AtomicLong(0);
        private final AtomicLong responseTimeOver1000ms = new AtomicLong(0);

        // 응답 시간 통계
        private double minResponseTimeMs = 0;
        private double maxResponseTimeMs = 0;
        private double avgResponseTimeMs = 0;

        // 요청 시작 시간 추적 (스레드 ID -> 시작 시간)
        private final Map<Long, Long> requestStartTimes = new ConcurrentHashMap<>();
    }
}