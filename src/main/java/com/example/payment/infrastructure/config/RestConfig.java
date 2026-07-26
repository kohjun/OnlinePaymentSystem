package com.example.payment.infrastructure.config;

import com.example.payment.infrastructure.gateway.TossPayoutsProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 외부 호출용 HTTP 클라이언트.
 *
 * 두 클라이언트 모두 RestTemplateBuilder로 만든다. new RestTemplate()으로
 * 직접 만들면 Spring Boot의 관찰(observation) 배선을 타지 않아 나가는 호출에
 * 스팬이 생기지 않는다. 결제 승인과 정산 송금은 외부 지연이 그대로 사용자
 * 대기 시간이 되는 구간이라, 추적에서 빠지면 정작 봐야 할 곳이 비게 된다.
 */
@Configuration
public class RestConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 정산 송금 전용 클라이언트.
     *
     * 공용 RestTemplate의 읽기 타임아웃(10초)은 은행 송금에 짧다. 타임아웃은
     * 곧 결과 미상(UNKNOWN)을 뜻하고, 그러면 재조정 워커가 제공자에 상태를
     * 되물어야 한다. 실제로는 성공한 송금을 불필요하게 미상으로 만들지 않도록
     * 별도 타임아웃을 준다.
     */
    @Bean
    public RestTemplate payoutsRestTemplate(RestTemplateBuilder builder, TossPayoutsProperties properties) {
        return builder
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build();
    }
}
