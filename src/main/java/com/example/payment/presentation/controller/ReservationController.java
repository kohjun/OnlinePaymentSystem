package com.example.payment.presentation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.payment.application.service.ReservationService;
import com.example.payment.presentation.dto.request.ReservationRequest;
import com.example.payment.presentation.dto.response.ReservationStatusResponse;
import com.example.payment.infrastructure.util.RateLimiter;

/**
 * 재고 선점 및 예약 관리 컨트롤러
 * - 한정 상품의 재고를 빠르게 선점하는 API
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Slf4j
@Validated
public class ReservationController {

    private final ReservationService reservationService;
    private final RateLimiter rateLimiter;

    /**
     * 재고 즉시 선점 (한정 상품 예약의 1단계)
     * POST /api/reservations
     */
    @PostMapping
    public ResponseEntity<ReservationStatusResponse> reserveInventory(@Valid @RequestBody ReservationRequest request) {

        log.info("Reservation request received: productId={}, customerId={}, quantity={}",
                request.getProductId(), request.getCustomerId(), request.getQuantity());

        // 1. 속도 제한 확인
        String rateLimitKey = request.getCustomerId() + ":" + request.getProductId();
        if (!rateLimiter.allowRequest(rateLimitKey)) {
            log.warn("Rate limit exceeded for customer: {}, product: {}",
                    request.getCustomerId(), request.getProductId());

            return ResponseEntity.status(429).body(
                    ReservationStatusResponse.failed(
                            request.getProductId(),
                            request.getQuantity(),
                            "RATE_LIMIT_EXCEEDED",
                            "너무 많은 요청입니다. 잠시 후 다시 시도해주세요."
                    )
            );
        }

        // 2. 재고 선점 시도
        ReservationStatusResponse response = reservationService.reserveInventory(
                request.getProductId(),
                request.getCustomerId(),
                request.getQuantity(),
                request.getClientId()
        );

        // 3. 응답 처리
        if ("RESERVED".equals(response.getStatus())) {
            log.info("Reservation successful: reservationId={}, customerId={}",
                    response.getReservationId(), request.getCustomerId());
            return ResponseEntity.ok(response);
        } else {
            log.warn("Reservation failed: productId={}, customerId={}, reason={}",
                    request.getProductId(), request.getCustomerId(), response.getMessage());
            return ResponseEntity.status(409).body(response); // Conflict
        }
    }

    /**
     * 예약 상태 조회
     * GET /api/reservations/{reservationId}
     */
    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationStatusResponse> getReservationStatus(@PathVariable String reservationId) {

        log.debug("Getting reservation status: reservationId={}", reservationId);

        ReservationStatusResponse response = reservationService.getReservationStatus(reservationId);

        if (response != null) {
            return ResponseEntity.ok(response);
        } else {
            log.warn("Reservation not found: reservationId={}", reservationId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 예약 취소 (사용자 요청)
     * DELETE /api/reservations/{reservationId}
     */
    @DeleteMapping("/{reservationId}")
    public ResponseEntity<String> cancelReservation(@PathVariable String reservationId,
                                                    @RequestParam String customerId) {

        log.info("Reservation cancellation requested: reservationId={}, customerId={}",
                reservationId, customerId);

        try {
            boolean cancelled = reservationService.cancelReservation(reservationId, customerId);

            if (cancelled) {
                log.info("Reservation cancelled successfully: reservationId={}", reservationId);
                return ResponseEntity.ok("예약이 취소되었습니다. 재고가 다시 사용 가능합니다.");
            } else {
                log.warn("Failed to cancel reservation: reservationId={}, customerId={}",
                        reservationId, customerId);
                return ResponseEntity.badRequest().body("예약 취소에 실패했습니다. 이미 확정되었거나 존재하지 않는 예약입니다.");
            }

        } catch (Exception e) {
            log.error("Error cancelling reservation: reservationId={}, customerId={}",
                    reservationId, customerId, e);
            return ResponseEntity.internalServerError().body("시스템 오류가 발생했습니다.");
        }
    }

    /**
     * 고객의 활성 예약 목록 조회 (선택적 기능)
     * GET /api/reservations/customer/{customerId}/active
     */
    @GetMapping("/customer/{customerId}/active")
    public ResponseEntity<String> getActiveReservations(@PathVariable String customerId) {
        // TODO: 구현 필요 (고객별 활성 예약 목록)
        return ResponseEntity.ok("구현 예정: 고객 " + customerId + "의 활성 예약 목록");
    }
}