package com.example.payment.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentProcessorService {

    private final CacheService cacheService;

    @KafkaListener(topics = "payment-requests", groupId = "payment-group")
    public void processPayment(String request) {
        log.info("Payment request received: {}", request);

        try {
            // 결제 처리 로직 시뮬레이션 (실제 구현에서는 더 복잡한 로직이 필요)
            Thread.sleep(100); // 결제 처리 시간 시뮬레이션

            // 처리 완료 후 캐시 업데이트
            String cacheKey = "payment:" + request.hashCode();
            cacheService.cacheData(cacheKey, "COMPLETED", 300);

            log.info("Payment processed successfully: {}", request);
        } catch (Exception e) {
            log.error("Error processing payment: {}", e.getMessage(), e);
        }
    }
}