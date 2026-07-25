package com.example.payment;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manual load probe for the production ticket-seat hold endpoint.
 */
class StressTestRunner {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String EVENT_ID = "EVT-CONCERT-VIP";
    private static final int CONCURRENT_USERS = 50;
    private static final int TEST_DURATION_SECONDS = 15;

    @Test
    @Disabled("Run manually against a local server with Docker dependencies available.")
    void runTicketHoldStressTest() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        assertServerAvailable(client);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        AtomicInteger totalRequests = new AtomicInteger();
        AtomicInteger acceptedResponses = new AtomicInteger();
        AtomicInteger unexpectedResponses = new AtomicInteger();
        AtomicLong totalLatencyMs = new AtomicLong();
        AtomicLong maxLatencyMs = new AtomicLong();
        AtomicLong minLatencyMs = new AtomicLong(Long.MAX_VALUE);
        long startedAt = System.currentTimeMillis();
        long endsAt = startedAt + TEST_DURATION_SECONDS * 1_000L;
        CountDownLatch startLatch = new CountDownLatch(1);

        for (int thread = 0; thread < CONCURRENT_USERS; thread++) {
            executor.submit(() -> runCustomerLoop(
                    client,
                    startLatch,
                    endsAt,
                    totalRequests,
                    acceptedResponses,
                    unexpectedResponses,
                    totalLatencyMs,
                    maxLatencyMs,
                    minLatencyMs
            ));
        }

        startLatch.countDown();
        Thread.sleep(TEST_DURATION_SECONDS * 1_000L);
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        int total = totalRequests.get();
        double durationSeconds = (System.currentTimeMillis() - startedAt) / 1_000.0;
        double averageLatency = total == 0 ? 0 : (double) totalLatencyMs.get() / total;
        System.out.printf(
                "Ticket hold load result: total=%d, accepted=%d, unexpected=%d, tps=%.2f, avg=%.2fms, min=%dms, max=%dms%n",
                total,
                acceptedResponses.get(),
                unexpectedResponses.get(),
                total / durationSeconds,
                averageLatency,
                total == 0 ? 0 : minLatencyMs.get(),
                maxLatencyMs.get()
        );
    }

    private void runCustomerLoop(HttpClient client,
                                 CountDownLatch startLatch,
                                 long endsAt,
                                 AtomicInteger totalRequests,
                                 AtomicInteger acceptedResponses,
                                 AtomicInteger unexpectedResponses,
                                 AtomicLong totalLatencyMs,
                                 AtomicLong maxLatencyMs,
                                 AtomicLong minLatencyMs) {
        try {
            startLatch.await();
            while (System.currentTimeMillis() < endsAt) {
                int seatNumber = ThreadLocalRandom.current().nextInt(1, 25);
                String seatId = EVENT_ID + "-SEAT-" + String.format("%04d", seatNumber);
                String customerId = "STRESS-CUST-" + ThreadLocalRandom.current().nextInt(10_000);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/marketplace/events/" + EVENT_ID
                                + "/tickets/seats/" + seatId + "/hold"))
                        .header("X-EverySale-User-Id", "USER-" + customerId)
                        .header("X-EverySale-Customer-Id", customerId)
                        .header("X-EverySale-Roles", "CUSTOMER")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(5))
                        .build();

                long requestStartedAt = System.nanoTime();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedAt);
                totalRequests.incrementAndGet();
                totalLatencyMs.addAndGet(latencyMs);
                maxLatencyMs.accumulateAndGet(latencyMs, Math::max);
                minLatencyMs.accumulateAndGet(latencyMs, Math::min);
                if (response.statusCode() == 200 || response.statusCode() == 409) {
                    acceptedResponses.incrementAndGet();
                } else {
                    unexpectedResponses.incrementAndGet();
                }
                Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));
            }
        } catch (Exception exception) {
            unexpectedResponses.incrementAndGet();
        }
    }

    private void assertServerAvailable(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/system/health"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Server health check failed with HTTP " + response.statusCode());
        }
    }
}
