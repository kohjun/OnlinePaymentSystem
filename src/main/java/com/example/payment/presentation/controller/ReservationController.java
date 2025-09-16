package com.example.payment.presentation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.payment.application.service.ReservationOrchestrator;
import com.example.payment.application.service.ReservationService;
import com.example.payment.presentation.dto.request.ReservationRequest;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.ReservationStatusResponse;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.infrastructure.util.RateLimiter;

/**
 * 예약 관리 컨트롤러 (오케스트레이터 패턴 적용)
 * - 통합 예약 플로우 (선점→주문→결제→확정)
 * - 기존 단계별 API 호환성 유지
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Slf4j
@Validated
public class ReservationController {

    private final ReservationOrchestrator reservationOrchestrator; // 오케스트레이터 (메인)
    private final ReservationService reservationService;           // 개별 조회용 (호환성)
    private final RateLimiter rateLimiter;

    // ========================================
    // 새로운 통합 API (권장 방식)
    // ========================================

    /**
     * 통합 예약 플로우 - 재고선점부터 결제확정까지 한번에
     * POST /api/reservations/complete
     */
    @PostMapping("/complete")
    public ResponseEntity<CompleteReservationResponse> createCompleteReservation(
            @Valid @RequestBody CompleteReservationRequest request) {

        log.info("Complete reservation request: customerId={}, productId={}, quantity={}, amount={}",
                request.getCustomerId(), request.getProductId(), request.getQuantity(), request.getAmount());

        // 1. 속도 제한 확인
        String rateLimitKey = request.getCustomerId() + ":" + request.getProductId();
        if (!rateLimiter.allowRequest(rateLimitKey)) {
            log.warn("Rate limit exceeded for complete reservation: customerId={}, productId={}",
                    request.getCustomerId(), request.getProductId());

            return ResponseEntity.status(429).body(
                    CompleteReservationResponse.failed("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.")
            );
        }

        // 2. 오케스트레이터에 전체 플로우 위임
        CompleteReservationResponse result = reservationOrchestrator.processCompleteReservation(request);

        // 3. 응답 처리
        if ("SUCCESS".equals(result.getStatus())) {
            log.info("Complete reservation succeeded: reservationId={}, orderId={}, paymentId={}",
                    result.getReservationId(), result.getOrderId(), result.getPaymentId());
            return ResponseEntity.ok(result);
        } else {
            log.warn("Complete reservation failed: customerId={}, productId={}, reason={}",
                    request.getCustomerId(), request.getProductId(), result.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ========================================
    // 기존 단계별 API (하위 호환성 유지)
    // ========================================

    /**
     * 재고 선점만 (기존 API 유지)
     * POST /api/reservations
     */
    @PostMapping
    public ResponseEntity<ReservationStatusResponse> reserveInventory(@Valid @RequestBody ReservationRequest request) {

        log.info("Inventory reservation request: productId={}, customerId={}, quantity={}",
                request.getProductId(), request.getCustomerId(), request.getQuantity());

        // 1. 속도 제한 확인
        String rateLimitKey = request.getCustomerId() + ":" + request.getProductId();
        if (!rateLimiter.allowRequest(rateLimitKey)) {
            log.warn("Rate limit exceeded for inventory reservation: customerId={}, productId={}",
                    request.getCustomerId(), request.getProductId());

            return ResponseEntity.status(429).body(
                    ReservationStatusResponse.builder()
                            .productId(request.getProductId())
                            .quantity(request.getQuantity())
                            .status("RATE_LIMITED")
                            .errorCode("RATE_LIMIT_EXCEEDED")
                            .message("너무 많은 요청입니다. 잠시 후 다시 시도해주세요.")
                            .remainingSeconds(0L)
                            .build()
            );
        }

        // 2. 오케스트레이터를 통해 개별 예약 상태 조회 (일관성 유지)
        ReservationStatusResponse response = reservationOrchestrator.createInventoryReservationOnly(
                request.getProductId(),
                request.getCustomerId(),
                request.getQuantity(),
                request.getClientId()
        );

        // 3. 응답 처리
        if ("RESERVED".equals(response.getStatus())) {
            log.info("Inventory reservation successful: reservationId={}, customerId={}",
                    response.getReservationId(), request.getCustomerId());
            return ResponseEntity.ok(response);
        } else {
            log.warn("Inventory reservation failed: productId={}, customerId={}, reason={}",
                    request.getProductId(), request.getCustomerId(), response.getMessage());
            return ResponseEntity.status(409).body(response); // Conflict
        }
    }

    // ========================================
    // 조회 및 관리 API들
    // ========================================

    /**
     * 예약 상태 조회
     * GET /api/reservations/{reservationId}
     */
    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationStatusResponse> getReservationStatus(@PathVariable String reservationId) {

        log.debug("Getting reservation status: reservationId={}", reservationId);

        // 오케스트레이터를 통해 통합된 상태 조회
        ReservationStatusResponse response = reservationOrchestrator.getReservationStatus(reservationId);

        if (response != null) {
            return ResponseEntity.ok(response);
        } else {
            log.warn("Reservation not found: reservationId={}", reservationId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 통합 예약 결과 조회 (예약→주문→결제 정보 모두 포함)
     * GET /api/reservations/{reservationId}/complete
     */
    @GetMapping("/{reservationId}/complete")
    public ResponseEntity<CompleteReservationResponse> getCompleteReservationStatus(@PathVariable String reservationId) {

        log.debug("Getting complete reservation status: reservationId={}", reservationId);

        CompleteReservationResponse response = reservationOrchestrator.getCompleteReservationStatus(reservationId);

        if (response != null) {
            return ResponseEntity.ok(response);
        } else {
            log.warn("Complete reservation not found: reservationId={}", reservationId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 예약 취소 (사용자 요청)
     * DELETE /api/reservations/{reservationId}
     */
    @DeleteMapping("/{reservationId}")
    public ResponseEntity<String> cancelReservation(@PathVariable String reservationId,
                                                    @RequestParam String customerId,
                                                    @RequestParam(required = false) String reason) {

        log.info("Reservation cancellation requested: reservationId={}, customerId={}, reason={}",
                reservationId, customerId, reason);

        try {
            String cancelReason = reason != null ? reason : "고객 요청";

            // 오케스트레이터를 통해 통합 취소 처리 (주문/결제도 함께 취소)
            boolean cancelled = reservationOrchestrator.cancelCompleteReservation(
                    reservationId,
                    customerId,
                    cancelReason
            );

            if (cancelled) {
                log.info("Reservation cancelled successfully: reservationId={}", reservationId);
                return ResponseEntity.ok("예약이 취소되었습니다. 관련 주문 및 결제도 함께 취소되었습니다.");
            } else {
                log.warn("Failed to cancel reservation: reservationId={}, customerId={}",
                        reservationId, customerId);
                return ResponseEntity.badRequest().body(
                        "예약 취소에 실패했습니다. 이미 확정되었거나 존재하지 않는 예약입니다.");
            }

        } catch (Exception e) {
            log.error("Error cancelling reservation: reservationId={}, customerId={}",
                    reservationId, customerId, e);
            return ResponseEntity.internalServerError().body("시스템 오류가 발생했습니다.");
        }
    }

    /**
     * 고객의 활성 예약 목록 조회
     * GET /api/reservations/customer/{customerId}/active
     */
    @GetMapping("/customer/{customerId}/active")
    public ResponseEntity<java.util.List<ReservationStatusResponse>> getActiveReservations(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.debug("Getting active reservations for customer: customerId={}, page={}, size={}",
                customerId, page, size);

        try {
            java.util.List<ReservationStatusResponse> reservations =
                    reservationOrchestrator.getActiveReservationsByCustomer(customerId, page, size);

            return ResponseEntity.ok(reservations);

        } catch (Exception e) {
            log.error("Error getting active reservations for customer: customerId={}", customerId, e);
            return ResponseEntity.internalServerError().body(java.util.Collections.emptyList());
        }
    }

    /**
     * 고객의 완료된 예약 목록 조회 (주문/결제 포함)
     * GET /api/reservations/customer/{customerId}/complete
     */
    @GetMapping("/customer/{customerId}/complete")
    public ResponseEntity<java.util.List<CompleteReservationResponse>> getCompleteReservations(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.debug("Getting complete reservations for customer: customerId={}, page={}, size={}",
                customerId, page, size);

        try {
            java.util.List<CompleteReservationResponse> reservations =
                    reservationOrchestrator.getCompleteReservationsByCustomer(customerId, page, size);

            return ResponseEntity.ok(reservations);

        } catch (Exception e) {
            log.error("Error getting complete reservations for customer: customerId={}", customerId, e);
            return ResponseEntity.internalServerError().body(java.util.Collections.emptyList());
        }
    }

    // ========================================
    // 관리자용 API들 (선택적)
    // ========================================

    /**
     * 상품별 예약 통계 조회 (관리자용)
     * GET /api/reservations/product/{productId}/stats
     */
    @GetMapping("/product/{productId}/stats")
    public ResponseEntity<java.util.Map<String, Object>> getReservationStats(@PathVariable String productId) {

        log.debug("Getting reservation stats for product: productId={}", productId);

        try {
            java.util.Map<String, Object> stats = reservationOrchestrator.getReservationStatsByProduct(productId);
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("Error getting reservation stats for product: productId={}", productId, e);
            return ResponseEntity.internalServerError().body(
                    java.util.Map.of("error", "통계 조회 실패: " + e.getMessage())
            );
        }
    }

    /**
     * 시스템 예약 현황 조회 (관리자용)
     * GET /api/reservations/system/status
     */
    @GetMapping("/system/status")
    public ResponseEntity<java.util.Map<String, Object>> getSystemReservationStatus() {

        log.debug("Getting system reservation status");

        try {
            java.util.Map<String, Object> status = reservationOrchestrator.getSystemReservationStatus();
            return ResponseEntity.ok(status);

        } catch (Exception e) {
            log.error("Error getting system reservation status", e);
            return ResponseEntity.internalServerError().body(
                    java.util.Map.of("error", "시스템 상태 조회 실패: " + e.getMessage())
            );
        }
    }

    // ========================================
    // 헬스체크 및 정보 API
    // ========================================

    /**
     * 예약 서비스 헬스체크
     * GET /api/reservations/health
     */
    @GetMapping("/health")
    public ResponseEntity<java.util.Map<String, Object>> healthCheck() {

        try {
            java.util.Map<String, Object> health = java.util.Map.of(
                    "status", "UP",
                    "service", "ReservationService",
                    "timestamp", System.currentTimeMillis(),
                    "version", "1.0"
            );

            return ResponseEntity.ok(health);

        } catch (Exception e) {
            log.error("Health check failed", e);
            return ResponseEntity.internalServerError().body(
                    java.util.Map.of(
                            "status", "DOWN",
                            "error", e.getMessage(),
                            "timestamp", System.currentTimeMillis()
                    )
            );
        }
    }
}