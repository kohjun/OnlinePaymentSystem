package com.example.payment.infrastructure.config;

import com.example.payment.infrastructure.gateway.TossPayoutsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);  // 연결 타임아웃 5초
        factory.setReadTimeout(10000);    // 읽기 타임아웃 10초

        return new RestTemplate(factory);
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
    public RestTemplate payoutsRestTemplate(TossPayoutsProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());

        return new RestTemplate(factory);
    }
}