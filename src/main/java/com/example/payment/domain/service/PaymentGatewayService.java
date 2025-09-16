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

    /**
     * 결제 환불
     */
    boolean refundPayment(String transactionId);

    /**
     * 결제 상태 조회
     */
    PaymentGatewayResult getPaymentStatus(String transactionId);

    /**
     * PG사 이름 반환
     */
    String getGatewayName();

    /**
     * 연결 상태 확인
     */
    boolean isHealthy();
}