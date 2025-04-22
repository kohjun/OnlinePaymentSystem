package com.example.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableKafka  // Kafka 이벤트 처리 활성화
public class PaymentApplication {  // 클래스명 변경하여 서비스 역할 명확화

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}