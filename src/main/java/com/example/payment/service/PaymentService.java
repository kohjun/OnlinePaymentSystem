package com.example.payment.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 재고 관리가 통합된 결제 처리 서비스
 * - Redis 기반 예약 패턴
 * - MySQL과의 재고 정합성 보장
 * - Kafka를 통한 이벤트 기반 아키텍처
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CacheService cacheService;
    private final PaymentEventService eventService;
    private final InventoryManagementService inventoryService;
    private final ObjectMapper objectMapper;

    // 결제 예약 TTL (초)
    private static final int PAYMENT_RESERVATION_TTL = 900; // 15분
    // 결제 완료 데이터 캐시 TTL (초)
    private static final int PAYMENT_COMPLETED_TTL = 3600; // 1시간

    /**
     * 결제 처리 (동기식) - 재고 연동
     * - 재고 예약 → 결제 처리 → 재고 확정 순서로 진행
     */
    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        try {
            // 1. 결제 ID 생성
            if (request.getPaymentId() == null) {
                request.setPaymentId(UUID.randomUUID().toString());
            }

            String reservationId = request.getIdempotencyKey() != null ?
                    request.getIdempotencyKey() : request.getPaymentId();

            log.info("Processing payment with inventory integration: paymentId={}, orderId={}",
                    request.getPaymentId(), request.getOrderId());

            // 2. 중복 결제 확인
            String cacheKey = "payment-reservation:" + reservationId;
            Object existingReservation = cacheService.getCachedData(cacheKey);
            if (existingReservation != null) {
                log.info("Payment reservation already exists: {}", reservationId);

                if (existingReservation instanceof PaymentResponse) {
                    return (PaymentResponse) existingReservation;
                }
            }

            // 3. 재고 예약 시도 (주문 관련 상품이 있는 경우)
            String inventoryReservationId = null;
            if (request.getOrderId() != null && hasInventoryItems(request)) {
                InventoryManagementService.InventoryReservationResult inventoryResult =
                        inventoryService.reserveInventory(
                                extractProductId(request), // 상품 ID 추출
                                reservationId,
                                extractQuantity(request), // 수량 추출
                                request.getOrderId(),
                                request.getPaymentId()
                        );

                if (!inventoryResult.isSuccess()) {
                    // 재고 예약 실패
                    PaymentResponse failedResponse = PaymentResponse.builder()
                            .paymentId(request.getPaymentId())
                            .orderId(request.getOrderId())
                            .amount(request.getAmount())
                            .currency(request.getCurrency())
                            .status("REJECTED")
                            .message("Inventory reservation failed: " + inventoryResult.getErrorMessage())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return failedResponse;
                }

                inventoryReservationId = inventoryResult.getReservationId();
                log.info("Inventory reserved successfully: reservationId={}, remaining={}",
                        inventoryReservationId, inventoryResult.getRemainingQuantity());
            }

            // 4. 결제 예약 생성
            PaymentResponse reservation = PaymentResponse.builder()
                    .paymentId(request.getPaymentId())
                    .orderId(request.getOrderId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .status("RESERVED")
                    .message("Payment and inventory reserved, processing payment")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            // 예약 정보를 캐시에 저장
            cacheService.cacheData(cacheKey, reservation, PAYMENT_RESERVATION_TTL);

            // 결제 예약 이벤트 발행
            eventService.publishPaymentCreated(reservation);

            // 5. 실제 결제 처리
            try {
                // 외부 결제 시스템 호출
                boolean paymentSuccessful = processExternalPayment(request);

                if (paymentSuccessful) {
                    // 6-1. 결제 성공 - 재고 확정
                    if (inventoryReservationId != null) {
                        boolean inventoryConfirmed = inventoryService.confirmReservation(inventoryReservationId);
                        if (!inventoryConfirmed) {
                            log.error("Payment succeeded but inventory confirmation failed: reservationId={}",
                                    inventoryReservationId);
                            // 심각한 상황 - 알림 필요
                            publishCriticalAlert("PAYMENT_SUCCESS_INVENTORY_FAIL", request.getPaymentId(),
                                    inventoryReservationId);
                        }
                    }

                    reservation.setStatus("COMPLETED");
                    reservation.setMessage("Payment processed successfully");

                    // 결제 성공 이벤트 발행
                    eventService.publishPaymentProcessed(reservation);

                } else {
                    // 6-2. 결제 실패 - 재고 예약 취소
                    if (inventoryReservationId != null) {
                        inventoryService.cancelReservation(inventoryReservationId);
                    }

                    reservation.setStatus("FAILED");
                    reservation.setMessage("Payment processing failed");

                    // 결제 실패 이벤트 발행
                    eventService.publishPaymentFailed(reservation);
                }

                reservation.setUpdatedAt(LocalDateTime.now());

                // 7. 최종 상태 캐싱
                cacheService.cacheData(cacheKey, reservation, PAYMENT_COMPLETED_TTL);
                cacheService.cacheData("payment:" + request.getPaymentId(), reservation, PAYMENT_COMPLETED_TTL);

                return reservation;

            } catch (Exception e) {
                log.error("Error during payment processing: {}", e.getMessage(), e);

                // 결제 처리 중 오류 - 재고 예약 취소
                if (inventoryReservationId != null) {
                    inventoryService.cancelReservation(inventoryReservationId);
                }

                reservation.setStatus("ERROR");
                reservation.setMessage("Payment processing error: " + e.getMessage());
                reservation.setUpdatedAt(LocalDateTime.now());

                // 에러 상태 캐싱
                cacheService.cacheData(cacheKey, reservation, PAYMENT_COMPLETED_TTL);

                // 실패 이벤트 발행
                eventService.publishPaymentFailed(reservation);

                return reservation;
            }

        } catch (Exception e) {
            log.error("Error in payment processing: {}", e.getMessage(), e);

            PaymentResponse errorResponse = PaymentResponse.builder()
                    .paymentId(request.getPaymentId())
                    .orderId(request.getOrderId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .status("ERROR")
                    .message("Payment processing error: " + e.getMessage())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            return errorResponse;
        }
    }

    /**
     * 비동기 결제 처리 - 재고 연동
     */
    public CompletableFuture<PaymentResponse> processPaymentAsync(PaymentRequest request) {
        return CompletableFuture.supplyAsync(() -> processPayment(request));
    }

    /**
     * 결제 상태 조회
     */
    public PaymentResponse getPaymentStatus(String paymentId) {
        // 직접 캐시 키 먼저 조회
        String directKey = "payment:" + paymentId;
        Object directData = cacheService.getCachedData(directKey);

        if (directData != null) {
            if (directData instanceof PaymentResponse) {
                return (PaymentResponse) directData;
            }
        }

        // 예약 캐시 키 조회
        String reservationKey = "payment-reservation:" + paymentId;
        Object reservationData = cacheService.getCachedData(reservationKey);

        if (reservationData != null) {
            if (reservationData instanceof PaymentResponse) {
                return (PaymentResponse) reservationData;
            } else {
                try {
                    return objectMapper.convertValue(reservationData, PaymentResponse.class);
                } catch (Exception e) {
                    log.error("Error converting payment data: {}", e.getMessage());
                }
            }
        }

        return null;
    }

    /**
     * 결제 취소 - 재고 연동
     */
    @Transactional
    public PaymentResponse cancelPayment(String paymentId) {
        PaymentResponse currentStatus = getPaymentStatus(paymentId);

        if (currentStatus == null) {
            return PaymentResponse.builder()
                    .paymentId(paymentId)
                    .status("ERROR")
                    .message("Payment not found")
                    .updatedAt(LocalDateTime.now())
                    .build();
        }

        // 취소 가능한 상태 확인
        if (!"RESERVED".equals(currentStatus.getStatus()) &&
                !"PROCESSING".equals(currentStatus.getStatus())) {
            return currentStatus;
        }

        try {
            // 관련 재고 예약 취소
            if (currentStatus.getOrderId() != null) {
                // 예약 ID는 결제 ID 또는 멱등성 키와 동일하다고 가정
                boolean inventoryCancelled = inventoryService.cancelReservation(paymentId);
                if (!inventoryCancelled) {
                    log.warn("Failed to cancel inventory reservation for payment: {}", paymentId);
                }
            }

            // 결제 취소 상태로 업데이트
            PaymentResponse cancelledResponse = PaymentResponse.builder()
                    .paymentId(currentStatus.getPaymentId())
                    .orderId(currentStatus.getOrderId())
                    .amount(currentStatus.getAmount())
                    .currency(currentStatus.getCurrency())
                    .status("CANCELLED")
                    .message("Payment has been cancelled")
                    .createdAt(currentStatus.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();

            // 취소 상태 캐싱
            String reservationKey = "payment-reservation:" + paymentId;
            cacheService.cacheData(reservationKey, cancelledResponse, PAYMENT_COMPLETED_TTL);
            cacheService.cacheData("payment:" + paymentId, cancelledResponse, PAYMENT_COMPLETED_TTL);

            // 취소 이벤트 발행
            eventService.publishPaymentFailed(cancelledResponse);

            return cancelledResponse;

        } catch (Exception e) {
            log.error("Error cancelling payment: {}", e.getMessage(), e);

            return PaymentResponse.builder()
                    .paymentId(paymentId)
                    .status("ERROR")
                    .message("Error cancelling payment: " + e.getMessage())
                    .updatedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 외부 결제 시스템 호출 시뮬레이션
     */
    private boolean processExternalPayment(PaymentRequest request) throws Exception {
        // 실패 시나리오 시뮬레이션 (5% 확률)
        if (Math.random() < 0.05) {
            log.warn("Simulated payment gateway error for payment ID: {}", request.getPaymentId());
            throw new Exception("External payment system error");
        }

        // 처리 시간 시뮬레이션
        Thread.sleep((long) (100 + Math.random() * 300));

        // 성공/실패 시뮬레이션 (90% 성공율)
        boolean success = Math.random() >= 0.1;
        log.info("Payment processing result for ID {}: {}",
                request.getPaymentId(), success ? "SUCCESS" : "FAILURE");

        return success;
    }

    /**
     * 재고 관련 상품이 있는지 확인
     */
    private boolean hasInventoryItems(PaymentRequest request) {
        // 실제 구현에서는 주문 정보를 조회하여 재고 관리 대상 상품이 있는지 확인
        // 현재는 orderId가 있으면 재고 관리 대상으로 간주
        return request.getOrderId() != null;
    }

    /**
     * 요청에서 상품 ID 추출
     */
    private String extractProductId(PaymentRequest request) {
        // 실제 구현에서는 주문 정보를 조회하여 상품 ID를 추출
        // 현재는 데모용으로 고정값 반환
        return "PROD-001"; // 실제로는 order 정보에서 추출
    }

    /**
     * 요청에서 수량 추출
     */
    private int extractQuantity(PaymentRequest request) {
        // 실제 구현에서는 주문 정보를 조회하여 수량을 추출
        // 현재는 데모용으로 고정값 반환
        return 1; // 실제로는 order 정보에서 추출
    }

    /**
     * 심각한 상황 알림 발행
     */
    private void publishCriticalAlert(String alertType, String paymentId, String reservationId) {
        try {
            Map<String, Object> alert = new HashMap<>();
            alert.put("alertType", alertType);
            alert.put("paymentId", paymentId);
            alert.put("reservationId", reservationId);
            alert.put("timestamp", System.currentTimeMillis());
            alert.put("severity", "CRITICAL");

            String alertJson = objectMapper.writeValueAsString(alert);
            kafkaTemplate.send("critical-alerts", alertType, alertJson);

            log.error("CRITICAL ALERT: {} - Payment: {}, Reservation: {}",
                    alertType, paymentId, reservationId);

        } catch (Exception e) {
            log.error("Error publishing critical alert: {}", e.getMessage(), e);
        }
    }

    /**
     * 기존 메서드 호환성 유지
     */
    public PaymentResponse initiatePayment(PaymentRequest request) {
        // 비동기 처리 시작
        CompletableFuture.runAsync(() -> {
            try {
                processPayment(request);
            } catch (Exception e) {
                log.error("Async payment processing failed: {}", e.getMessage(), e);
            }
        });

        // 예약 상태 즉시 반환
        return PaymentResponse.builder()
                .paymentId(request.getPaymentId())
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status("PROCESSING")
                .message("Payment request is being processed asynchronously")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}