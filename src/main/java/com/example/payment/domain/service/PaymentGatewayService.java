package com.example.payment.domain.service;

import com.example.payment.application.dto.PaymentGatewayRequest;
import com.example.payment.application.dto.PaymentGatewayResult;

/**
 * 결제 게이트웨이 도메인 서비스 인터페이스
 * - 도메인의 핵심 비즈니스 규칙을 나타냄
 * - 인프라스트럭처 레이어에서 구현
 * - DIP(의존성 역전 원칙) 적용
 */
public interface PaymentGatewayService {

    /**
     * 결제 처리
     */
    PaymentGatewayResult processPayment(PaymentGatewayRequest request);

    default PaymentGatewayResult authorize(PaymentGatewayRequest request) {
        return processPayment(request);
    }

    /**
     * 결제 환불
     */
    boolean refundPayment(String transactionId);

    /**
     * 결제 상태 조회
     */
    PaymentGatewayResult getPaymentStatus(String transactionId);

    /**
     * 결제 ID(주문 식별 키) 기준 결제 상태 조회 (타임아웃 복구용)
     */
    default PaymentGatewayResult getPaymentStatusByPaymentId(String paymentId) {
        return PaymentGatewayResult.failure("NOT_SUPPORTED", "이 PG사는 결제 ID 조회를 지원하지 않습니다.");
    }

    /**
     * PG사 이름 반환
     */
    String getGatewayName();

    /**
     * 연결 상태 확인
     */
    boolean isHealthy();

    default boolean supports(String paymentMethod) {
        return true;
    }
}
