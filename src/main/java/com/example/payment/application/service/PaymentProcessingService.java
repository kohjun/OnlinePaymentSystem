/**
 * ========================================
 * 2. PaymentProcessingService (결제 처리 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.model.ReservationState;
import com.example.payment.domain.model.CompletedOrder;
import com.example.payment.domain.repository.ReservationRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.presentation.dto.request.PaymentRequest;
import com.example.payment.presentation.dto.response.PaymentResponse;
import com.example.payment.application.dto.ReservationValidationResult;
import com.example.payment.application.dto.PaymentGatewayResult;
import com.example.payment.application.dto.CreateOrderRequest;
import com.example.payment.application.event.publisher.PaymentEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentProcessingService {

    private final ResourceReservationService redisReservationService;
    private final ReservationRepository reservationRepository;
    private final CacheService cacheService;
    private final PaymentEventPublisher eventService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OrderService orderService; // ← OrderService 의존성 추가

    /**
     * 예약 기반 결제 처리 (결제 성공 시 주문ID 생성)
     */
    @Transactional(timeout = 30)
    public PaymentResponse processReservationPayment(PaymentRequest request) {
        log.info("Processing reservation payment: reservationId={}, customerId={}, amount={}",
                request.getReservationId(), request.getCustomerId(), request.getAmount());

        try {
            // 1. 예약 유효성 검증
            ReservationValidationResult validation = validateReservation(request.getReservationId(),
                    request.getCustomerId());

            if (!validation.isValid()) {
                log.warn("Reservation validation failed: reservationId={}, reason={}",
                        request.getReservationId(), validation.getErrorMessage());

                return PaymentResponse.builder()
                        .paymentId(request.getPaymentId())
                        .reservationId(request.getReservationId())
                        .amount(request.getAmount())
                        .currency(request.getCurrency())
                        .status("FAILED")
                        .message(validation.getErrorMessage())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            }

            // 2. 결제 ID 생성 (없는 경우)
            if (request.getPaymentId() == null) {
                request.setPaymentId(IdGenerator.generatePaymentId());
            }

            // 3. 외부 PG 결제 요청 시뮬레이션
            PaymentGatewayResult pgResult = processExternalPayment(request);

            if (!pgResult.isSuccess()) {
                log.error("Payment gateway failed: reservationId={}, error={}",
                        request.getReservationId(), pgResult.getErrorMessage());

                return PaymentResponse.builder()
                        .paymentId(request.getPaymentId())
                        .reservationId(request.getReservationId())
                        .amount(request.getAmount())
                        .currency(request.getCurrency())
                        .status("FAILED")
                        .message("결제 처리 실패: " + pgResult.getErrorMessage())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            }

            // 4. 결제 성공 시 예약 확정 + 주문 생성
            String orderId = confirmReservationAndCreateOrder(request, pgResult, validation.getReservation());

            // 5. 성공 응답 생성
            PaymentResponse successResponse = PaymentResponse.builder()
                    .paymentId(request.getPaymentId())
                    .orderId(orderId)  // ← 결제 성공 후 주문ID 생성!
                    .reservationId(request.getReservationId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .status("COMPLETED")
                    .message("결제가 완료되었습니다. 주문번호: " + orderId)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            // 6. 결제 완료 이벤트 발행
            eventService.publishPaymentProcessed(successResponse);

            // 7. 비동기 후처리를 위한 주문 완료 이벤트 발행
            publishOrderCompletedEvent(orderId, request.getReservationId(), request.getCustomerId());

            log.info("Payment processing completed successfully: orderId={}, paymentId={}",
                    orderId, request.getPaymentId());

            return successResponse;

        } catch (Exception e) {
            log.error("Error processing reservation payment: reservationId={}",
                    request.getReservationId(), e);

            return PaymentResponse.builder()
                    .paymentId(request.getPaymentId())
                    .reservationId(request.getReservationId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .status("ERROR")
                    .message("결제 처리 중 시스템 오류가 발생했습니다: " + e.getMessage())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 예약 유효성 검증
     */
    private ReservationValidationResult validateReservation(String reservationId, String customerId) {
        try {
            // 캐시에서 예약 상태 조회
            String cacheKey = "reservation-state:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData == null) {
                return ReservationValidationResult.invalid("예약을 찾을 수 없습니다.");
            }

            ReservationState state;
            if (cachedData instanceof ReservationState) {
                state = (ReservationState) cachedData;
            } else {
                state = objectMapper.convertValue(cachedData, ReservationState.class);
            }

            // 만료 확인
            if (state.isExpired()) {
                return ReservationValidationResult.invalid("예약이 만료되었습니다.");
            }

            // 소유자 확인
            if (!customerId.equals(state.getCustomerId())) {
                return ReservationValidationResult.invalid("권한이 없습니다.");
            }

            // 상태 확인
            if (!"RESERVED".equals(state.getStatus())) {
                return ReservationValidationResult.invalid("예약 상태가 올바르지 않습니다: " + state.getStatus());
            }

            return ReservationValidationResult.valid(state);

        } catch (Exception e) {
            log.error("Error validating reservation: reservationId={}", reservationId, e);
            return ReservationValidationResult.invalid("예약 검증 중 오류가 발생했습니다.");
        }
    }

    /**
     * 외부 PG 결제 처리 시뮬레이션
     */
    private PaymentGatewayResult processExternalPayment(PaymentRequest request) throws Exception {
        // 결제 처리 시간 시뮬레이션
        Thread.sleep((long) (100 + Math.random() * 200));

        // 실패 시나리오 시뮬레이션 (5% 확률)
        if (Math.random() < 0.05) {
            return PaymentGatewayResult.builder()
                    .success(false)
                    .errorMessage("카드 승인이 거절되었습니다.")
                    .processedAt(LocalDateTime.now())
                    .build();
        }

        // 성공 시나리오
        return PaymentGatewayResult.builder()
                .success(true)
                .transactionId("TXN-" + System.currentTimeMillis())
                .processedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 예약 확정 및 주문 생성 (트랜잭션)
     */
    @Transactional
    private String confirmReservationAndCreateOrder(PaymentRequest request, PaymentGatewayResult pgResult,
                                                    ReservationState reservationState) {

        // 1. OrderService를 통해 주문 생성
        CreateOrderRequest orderRequest = CreateOrderRequest.builder()
                .customerId(request.getCustomerId())
                .productId(reservationState.getProductId())
                .quantity(reservationState.getQuantity())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .paymentId(request.getPaymentId())
                .reservationId(request.getReservationId())
                .build();

        String orderId = orderService.createOrder(orderRequest);

        // 2. Redis에서 예약 확정 처리
        boolean redisConfirmed = redisReservationService.confirmReservation(request.getReservationId());
        if (!redisConfirmed) {
            log.warn("Redis reservation confirmation failed: reservationId={}", request.getReservationId());
            // Redis 실패는 로그만 남기고 진행 (MySQL이 주 저장소)
        }

        // 3. 예약 상태 업데이트
        reservationState.setStatus("CONFIRMED");
        String reservationCacheKey = "reservation-state:" + request.getReservationId();
        cacheService.cacheData(reservationCacheKey, reservationState, 86400);

        log.info("Order created successfully: orderId={}, reservationId={}", orderId, request.getReservationId());

        return orderId;
    }

    /**
     * 주문 완료 이벤트 발행 (Kafka 비동기 후처리용)
     */
    private void publishOrderCompletedEvent(String orderId, String reservationId, String customerId) {
        try {
            String eventJson = objectMapper.writeValueAsString(java.util.Map.of(
                    "eventType", "ORDER_COMPLETED",
                    "orderId", orderId,
                    "reservationId", reservationId,
                    "customerId", customerId,
                    "timestamp", System.currentTimeMillis()
            ));

            kafkaTemplate.send("order-completed", orderId, eventJson);
            log.info("Order completed event published: orderId={}", orderId);

        } catch (Exception e) {
            log.error("Error publishing order completed event: orderId={}", orderId, e);
        }
    }

    /**
     * 결제 상태 조회
     */
    public PaymentResponse getPaymentStatus(String paymentId) {
        // 구현 로직은 기존과 유사하지만 reservationId도 포함하여 반환
        // 생략 (기존 코드 활용 가능)
        return null;
    }
}