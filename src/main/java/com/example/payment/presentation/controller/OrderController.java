package com.example.payment.presentation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 재고 선점 컨트롤러
 * 고트래픽용 최적화 설계
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final RateLimiter rateLimiter;

    /**
     * 재고 즉시 선점 (패턴 B의 1단계)
     * POST /api/orders/reserve
     */
    @PostMapping("/reserve")
    public ResponseEntity<OrderResponse> reserveInventory(@RequestBody OrderRequest request) {

        // 고트래픽용 속도 제한
        String rateLimitKey = request.getCustomerId() + ":" + request.getProductId();
        if (!rateLimiter.allowRequest(rateLimitKey)) {
            return ResponseEntity.status(429).body(
                    OrderResponse.failed(request.getProductId(), "RATE_LIMIT_EXCEEDED",
                            "너무 많은 요청입니다. 잠시 후 다시 시도해주세요.")
            );
        }

        // 재고 즉시 선점 시도
        OrderResponse response = orderService.reserveInventoryImmediately(request);

        if ("RESERVED".equals(response.getStatus())) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 예약 상태 조회 (캐시 기반 고속 조회)
     * GET /api/orders/reservations/{reservationId}
     */
    @GetMapping("/reservations/{reservationId}")
    public ResponseEntity<ReservationStatusResponse> getReservationStatus(@PathVariable String reservationId) {

        ReservationStatusResponse response = orderService.getReservationStatus(reservationId);

        if (response != null) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}