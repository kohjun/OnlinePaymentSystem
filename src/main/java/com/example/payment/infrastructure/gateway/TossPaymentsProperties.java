package com.example.payment.infrastructure.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "payment.toss")
public class TossPaymentsProperties {

    /**
     * 결제위젯 연동 키 접두사.
     *
     * Toss는 연동 키를 두 종류로 나눈다. 결제위젯 연동 키(test_gck_/live_gck_)는
     * widgets() 전용이고, API 개별 연동 키(test_ck_/live_ck_)는 결제창
     * payment() 전용이다. 이 애플리케이션은 결제창을 쓰므로 위젯 키를 받으면
     * SDK가 "결제위젯 연동 키는 지원하지 않습니다"로 거절한다. 그 실패를
     * 브라우저까지 끌고 가지 않고 서버에서 먼저 막는다.
     */
    private static final String WIDGET_CLIENT_KEY_MARKER = "_gck_";

    private String clientKey;
    private String secretKey;
    private String baseUrl = "https://api.tosspayments.com";
    private String mode = "test";
    private String apiVersion = "2022-11-16";
    private String testCode;
    private Webhook webhook = new Webhook();
    private Reconciliation reconciliation = new Reconciliation();

    /** 결제위젯 연동 키가 설정된 경우. 결제창 SDK에서는 쓸 수 없다. */
    public boolean isWidgetClientKey() {
        return clientKey != null && clientKey.contains(WIDGET_CLIENT_KEY_MARKER);
    }

    /** 결제창 연동에 쓸 수 있는 클라이언트 키가 준비된 경우. */
    public boolean hasUsablePaymentWindowClientKey() {
        return clientKey != null && !clientKey.isBlank() && !isWidgetClientKey();
    }

    /**
     * 설정 문제를 사람이 바로 고칠 수 있는 문장으로 돌려준다.
     * 문제가 없으면 null.
     */
    public String clientKeyProblem() {
        if (clientKey == null || clientKey.isBlank()) {
            return "Toss 클라이언트 키가 설정되지 않았습니다. .env의 TOSS_CLIENT_KEY를 채우고, "
                    + "gradlew bootRun 대신 scripts/run-local.ps1로 실행하세요. bootRun은 .env를 읽지 않습니다.";
        }
        if (isWidgetClientKey()) {
            return "결제위젯 연동 키는 지원하지 않습니다. Toss 개발자센터에서 'API 개별 연동 키'의 "
                    + "클라이언트 키(test_ck_ 또는 live_ck_)를 TOSS_CLIENT_KEY에 설정하세요.";
        }
        return null;
    }

    @Data
    public static class Webhook {
        private boolean enabled = false;
        private String pathToken;
        private long retryFixedDelayMs = 30000;
        private int maxRetry = 7;
    }

    @Data
    public static class Reconciliation {
        private boolean enabled = true;
        private long fixedDelayMs = 60000;
        private long staleSeconds = 300;
    }
}
