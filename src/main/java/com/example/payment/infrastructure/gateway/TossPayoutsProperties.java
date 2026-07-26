package com.example.payment.infrastructure.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 판매자 정산 송금(지급대행) 어댑터 설정.
 *
 * 결제 수납용 {@link TossPaymentsProperties}와 자격증명을 공유하지 않는다.
 * 지급대행은 돈이 나가는 방향이라 키 권한과 회전 주기가 다르게 관리되어야 한다.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.payout.transfer.toss")
public class TossPayoutsProperties {

    private String secretKey;
    private String baseUrl = "https://api.tosspayments.com";
    private String mode = "test";

    /**
     * 은행 송금은 결제 승인보다 응답이 느리다. 공용 RestTemplate의 읽기
     * 타임아웃(10초)을 그대로 쓰면 실제로는 처리된 송금을 타임아웃으로
     * 판단해 UNKNOWN으로 남기는 일이 잦아진다.
     */
    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs = 30_000;
}
