package com.example.payment;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 실시간 실행 중인 서버(http://localhost:8080)를 대상으로 대규모 동시 접속 부하를 가하는 스트레스 테스트 클래스입니다.
 * Gradle을 통해 쉽게 실행할 수 있습니다:
 * .\gradlew.bat test --tests "com.example.payment.StressTestRunner"
 */
public class StressTestRunner {

    private static final String BASE_URL = "http://localhost:8080";
    private static final int CONCURRENT_USERS = 50; // 동시 접속 가상 유저 수 (스레드 수)
    private static final int TEST_DURATION_SECONDS = 15; // 테스트 진행 시간

    @Test
    @Disabled("수동으로 스트레스 테스트를 실행할 때만 활성화하세요.")
    public void runStressTest() throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🔥 STARLIGHT TICKETS - 실시간 서버 스트레스 테스트 시작");
        System.out.println("   - 대상 서버: " + BASE_URL);
        System.out.println("   - 동시 접속 유저 수 (Threads): " + CONCURRENT_USERS);
        System.out.println("   - 테스트 진행 시간: " + TEST_DURATION_SECONDS + "초");
        System.out.println("=".repeat(60) + "\n");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        // 먼저 서버 헬스체크 진행
        try {
            HttpRequest healthRequest = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/system/health"))
                    .GET()
                    .build();
            HttpResponse<String> healthResponse = client.send(healthRequest, HttpResponse.BodyHandlers.ofString());
            if (healthResponse.statusCode() != 200) {
                throw new IllegalStateException("서버 헬스체크 실패: HTTP " + healthResponse.statusCode());
            }
            System.out.println("✅ 서버 연결 상태 양호 (HTTP 200). 스트레스 테스트를 시작합니다...\n");
        } catch (Exception e) {
            System.err.println("❌ 서버에 연결할 수 없습니다. 서버가 기동 중인지 확인해주세요 (http://localhost:8080).");
            System.err.println("   오류 메시지: " + e.getMessage());
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        AtomicInteger totalRequests = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        AtomicLong totalLatencyMs = new AtomicLong(0);
        AtomicLong maxLatencyMs = new AtomicLong(0);
        AtomicLong minLatencyMs = new AtomicLong(Long.MAX_VALUE);

        long testStartTime = System.currentTimeMillis();
        long testEndTime = testStartTime + (TEST_DURATION_SECONDS * 1000L);

        CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (System.currentTimeMillis() < testEndTime) {
                        long reqStartTime = System.nanoTime();
                        
                        // 랜덤 좌석 및 사용자 ID 생성
                        String seatId = "V-" + (ThreadLocalRandom.current().nextInt(24) + 1);
                        String customerId = "STRESS-CUST-" + ThreadLocalRandom.current().nextInt(10000);

                        // 1단계: 좌석 선점 API 호출
                        String lockUrl = BASE_URL + "/api/system/seats/lock?seatId=" + seatId + "&customerId=" + customerId;
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(lockUrl))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(5))
                                .build();

                        totalRequests.incrementAndGet();
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        
                        long reqEndTime = System.nanoTime();
                        long latencyMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(reqEndTime - reqStartTime);
                        
                        totalLatencyMs.addAndGet(latencyMs);
                        maxLatencyMs.accumulateAndGet(latencyMs, Math::max);
                        minLatencyMs.accumulateAndGet(latencyMs, Math::min);

                        if (response.statusCode() == 200 || response.statusCode() == 409) {
                            // 200: 선점 성공, 409: 이미 예매된 좌석 (정상 비즈니스 응답)
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }

                        // 부하 제어를 위해 미세한 딜레이 (10~50ms) 추가
                        Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });
        }

        // 테스트 시작!
        startLatch.countDown();
        
        // 지정된 시간 동안 대기
        Thread.sleep(TEST_DURATION_SECONDS * 1000L);

        executor.shutdown();
        if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        long actualDurationMs = System.currentTimeMillis() - testStartTime;
        double durationSeconds = actualDurationMs / 1000.0;
        int total = totalRequests.get();
        int success = successCount.get();
        int failure = failureCount.get();
        double tps = total / durationSeconds;
        double avgLatency = total > 0 ? (double) totalLatencyMs.get() / total : 0.0;

        System.out.println("\n" + "=".repeat(60));
        System.out.println("📊 스트레스 테스트 결과 요약");
        System.out.println("-".repeat(60));
        System.out.printf("⏱️ 실제 수행 시간: %.2f초\n", durationSeconds);
        System.out.printf("📦 총 요청 횟수  : %d회\n", total);
        System.out.printf("✅ 성공 응답 횟수: %d회 (성공률: %.2f%%)\n", success, (total > 0 ? (success * 100.0 / total) : 0.0));
        System.out.printf("❌ 오류 응답 횟수: %d회\n", failure);
        System.out.printf("🚀 초당 처리량(TPS): %.2f TPS\n", tps);
        System.out.printf("⏱️ 평균 응답 속도  : %.2f ms\n", avgLatency);
        System.out.printf("⚡ 최소 응답 속도  : %d ms\n", (total > 0 ? minLatencyMs.get() : 0));
        System.out.printf("🔥 최대 응답 속도  : %d ms\n", (total > 0 ? maxLatencyMs.get() : 0));
        System.out.println("=".repeat(60) + "\n");
    }
}
