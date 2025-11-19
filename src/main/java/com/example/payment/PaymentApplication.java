package com.example.payment;

import com.example.payment.infrastructure.lock.DistributedLockService; // DistributedLockService를 import
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.beans.factory.annotation.Autowired;


@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableKafka
@RequiredArgsConstructor
@Slf4j
public class PaymentApplication implements CommandLineRunner {

    private final ResourceReservationService resourceReservationService;

    // Lock 플래그 상태를 확인하기 위해 주입 (선택 사항이지만 유용)
    private final DistributedLockService distributedLockService;

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
    // 초기 재고 설정 변수 initialStock
    @Override
    public void run(String... args) throws Exception {
        String productId = "SAGA-TEST-001";
        
        int initialStock;
        initialStock = 200;
        log.warn("--- [TEST MODE] Lock Enabled. Setting Stock to 200 (Competition Mode) ---");

        // Redis 재고 초기화
        resourceReservationService.initializeResource(
                "inventory:" + productId,
                initialStock, // total quantity
                initialStock  // available quantity
        );
        log.warn("--- [Redis Init] 상품 {}의 재고를 {}개로 초기화 완료. ---", productId, initialStock);
    }
}