package com.example.payment.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j 설정
 * - Redis 클러스터 실패 시 서킷 브레이커 패턴 적용
 */
@Configuration
public class Resilience4jConfig {

    /**
     * CircuitBreaker 레지스트리 설정
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        // 기본 서킷 브레이커 설정
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                      // 50% 실패율에서 서킷 오픈
                .waitDurationInOpenState(Duration.ofSeconds(30)) // 열린 상태에서 30초 대기
                .permittedNumberOfCallsInHalfOpenState(5)      // 반열림 상태에서 5번의 호출 허용
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED) // 카운트 기반 슬라이딩 윈도우
                .slidingWindowSize(10)                         // 10개 요청 기준으로 실패율 계산
                .minimumNumberOfCalls(5)                       // 최소 5개 호출 이후 실패율 계산
                .automaticTransitionFromOpenToHalfOpenEnabled(true) // 시간 경과 시 자동으로 반열림 상태로 전환
                .build();

        // 서킷 브레이커 레지스트리 생성
        return CircuitBreakerRegistry.of(circuitBreakerConfig);
    }

    /**
     * Redis 전용 서킷 브레이커 생성
     */
    @Bean
    public CircuitBreaker redisCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker("redis");
    }
}