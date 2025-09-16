package com.example.payment.presentation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.payment.application.service.OrderService;
import com.example.payment.presentation.dto.response.OrderResponse;

/**
 * 주문 관리 컨트롤러
 * - 결제 완료 후 생성된 주문의 조회 및 관리
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
@Validated
public class OrderController {

    private final OrderService orderService;

    /**
     * 주문 상태 조회
     * GET /api/orders/{orderId}
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrderStatus(@PathVariable String orderId) {

        log.debug("Getting order status: orderId={}", orderId);

        OrderResponse response = orderService.getOrderStatus(orderId);

        if (response != null) {
            if ("NOT_FOUND".equals(response.getStatus())) {
                return ResponseEntity.notFound().build();
            } else if ("ERROR".equals(response.getStatus())) {
                return ResponseEntity.internalServerError().body(response);
            } else {
                return ResponseEntity.ok(response);
            }
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 주문 취소 (특정 조건에서만 가능)
     * DELETE /api/orders/{orderId}
     */
    @DeleteMapping("/{orderId}")
    public ResponseEntity<String> cancelOrder(@PathVariable String orderId,
                                              @RequestParam String customerId,
                                              @RequestParam(required = false) String reason) {

        log.info("Order cancellation requested: orderId={}, customerId={}, reason={}",
                orderId, customerId, reason);

        try {
            String cancelReason = reason != null ? reason : "고객 요청";
            boolean cancelled = orderService.cancelOrder(orderId, customerId, cancelReason);

            if (cancelled) {
                log.info("Order cancelled successfully: orderId={}", orderId);
                return ResponseEntity.ok("주문이 취소되었습니다.");
            } else {
                log.warn("Failed to cancel order: orderId={}, customerId={}", orderId, customerId);
                return ResponseEntity.badRequest().body("주문 취소에 실패했습니다. 이미 처리된 주문이거나 취소할 수 없는 상태입니다.");
            }

        } catch (Exception e) {
            log.error("Error cancelling order: orderId={}, customerId={}", orderId, customerId, e);
            return ResponseEntity.internalServerError().body("시스템 오류가 발생했습니다.");
        }
    }

    /**
     * 고객의 주문 목록 조회
     * GET /api/orders/customer/{customerId}
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<java.util.List<OrderResponse>> getCustomerOrders(@PathVariable String customerId,
                                                                           @RequestParam(defaultValue = "0") int page,
                                                                           @RequestParam(defaultValue = "10") int size) {

        log.debug("Getting customer orders: customerId={}, page={}, size={}", customerId, page, size);

        try {
            java.util.List<OrderResponse> orders = orderService.getCustomerOrders(customerId);
            return ResponseEntity.ok(orders);

        } catch (Exception e) {
            log.error("Error getting customer orders: customerId={}", customerId, e);
            return ResponseEntity.internalServerError().body(java.util.Collections.emptyList());
        }
    }

    /**
     * 예약 ID로 주문 조회 (편의 기능)
     * GET /api/orders/reservation/{reservationId}
     */
    @GetMapping("/reservation/{reservationId}")
    public ResponseEntity<String> getOrderByReservation(@PathVariable String reservationId) {
        // TODO: 예약 ID로 주문 조회 기능 구현
        log.debug("Getting order by reservation: reservationId={}", reservationId);
        return ResponseEntity.ok("구현 예정: 예약 " + reservationId + "에 대한 주문 정보");
    }
}