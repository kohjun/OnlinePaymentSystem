package com.example.payment.util;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("simulation")
@Slf4j
@RequiredArgsConstructor
public class TrafficSimulator implements CommandLineRunner {

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String PAYMENT_URL = "http://localhost:8080/api/payment/process";

    @Override
    public void run(String... args) {
        log.info("Starting traffic simulation...");

        int numberOfThreads = 10;
        int requestsPerThread = 100;

        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numberOfThreads; i++) {
            executorService.submit(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    try {
                        restTemplate.postForEntity(PAYMENT_URL, "payment-data-" + j, String.class);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }
            });
        }

        executorService.shutdown();

        try {
            // 모든 스레드가 완료될 때까지 대기
            while (!executorService.isTerminated()) {
                Thread.sleep(1000);
                log.info("Simulation in progress... Success: {}, Failures: {}",
                        successCount.get(), failureCount.get());
            }

            long endTime = System.currentTimeMillis();
            double durationSeconds = (endTime - startTime) / 1000.0;

            log.info("Simulation completed!");
            log.info("Total requests: {}", numberOfThreads * requestsPerThread);
            log.info("Successful requests: {}", successCount.get());
            log.info("Failed requests: {}", failureCount.get());
            log.info("Duration: {} seconds", durationSeconds);
            log.info("Throughput: {} requests/second", successCount.get() / durationSeconds);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Simulation interrupted", e);
        }
    }
}