package com.example.payment.infrastructure.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 결제창은 'API 개별 연동 키'만 받는다.
 *
 * 위젯 키를 넣어도 애플리케이션은 정상 기동하고, 사용자가 결제 버튼을
 * 누른 뒤에야 Toss SDK가 브라우저에서 거절한다. 그 시점의 메시지만으로는
 * 어느 키를 어디서 바꿔야 하는지 알기 어려워 여기서 먼저 막는다.
 */
class TossPaymentsPropertiesTest {

    private TossPaymentsProperties properties(String clientKey) {
        TossPaymentsProperties properties = new TossPaymentsProperties();
        properties.setClientKey(clientKey);
        return properties;
    }

    @Test
    @DisplayName("API 개별 연동 키는 결제창에 그대로 쓸 수 있다")
    void apiIndividualKeyIsUsable() {
        TossPaymentsProperties test = properties("test_ck_abcdef0123456789");
        assertFalse(test.isWidgetClientKey());
        assertTrue(test.hasUsablePaymentWindowClientKey());
        assertNull(test.clientKeyProblem());

        assertNull(properties("live_ck_abcdef0123456789").clientKeyProblem());
    }

    @Test
    @DisplayName("결제위젯 연동 키는 어떤 키를 써야 하는지 알려주며 거절한다")
    void widgetKeyIsRejectedWithActionableMessage() {
        TossPaymentsProperties widget = properties("test_gck_abcdef0123456789");

        assertTrue(widget.isWidgetClientKey());
        assertFalse(widget.hasUsablePaymentWindowClientKey());

        String problem = widget.clientKeyProblem();
        assertNotNull(problem);
        assertTrue(problem.contains("결제위젯 연동 키는 지원하지 않습니다"));
        assertTrue(problem.contains("API 개별 연동 키"), "바꿔야 할 키 종류를 알려줘야 합니다");
        assertTrue(problem.contains("test_ck_"), "기대하는 접두사를 알려줘야 합니다");

        assertTrue(properties("live_gck_abcdef0123456789").isWidgetClientKey());
    }

    @Test
    @DisplayName("키가 비어 있으면 .env를 읽는 실행 방법까지 함께 알려준다")
    void missingKeyExplainsHowItIsLoaded() {
        String problem = properties("  ").clientKeyProblem();

        assertNotNull(problem);
        assertTrue(problem.contains("TOSS_CLIENT_KEY"));
        // bootRun은 .env를 읽지 않는다. 이 함정을 여러 번 밟게 되는 지점이다.
        assertTrue(problem.contains("run-local.ps1"), "실행 방법을 알려줘야 합니다");

        assertNotNull(properties(null).clientKeyProblem(), "키가 null이어도 안내해야 합니다");
        assertFalse(properties(null).hasUsablePaymentWindowClientKey());
    }
}
