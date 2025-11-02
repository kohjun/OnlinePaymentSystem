package com.example.payment.presentation.controller;

import com.example.payment.infrastructure.util.IdGenerator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import lombok.RequiredArgsConstructor;
import com.example.payment.presentation.dto.response.OrderResponse;  // ✅ 추가
import java.util.Collections;  // ✅ 추가
import java.util.List;  // ✅ 추가
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

        OrderResponse response = OrderResponse.from(orderService.getOrder(orderId));

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
     * ✅ 고객의 주문 목록 조회
     */
    public List<OrderResponse> getCustomerOrders(String customerId) {
        try {
            // 실제 구현에서는 Repository에서 조회
            // 현재는 캐시만 사용하므로 빈 리스트 반환
            log.warn("getCustomerOrders not fully implemented yet: customerId={}", customerId);
            return Collections.emptyList();

        } catch (Exception e) {
            log.error("Error getting customer orders: customerId={}", customerId, e);
            return Collections.emptyList();
        }
    }
    /**
     * 주문 취소 (특정 조건에서만 가능)
     * DELETE /api/orders/{orderId}
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<String> cancelOrder(@PathVariable String orderId,
                                              @RequestParam String customerId,
                                              @RequestParam(required = false) String cancelReason) {

        log.info("Order cancellation requested: orderId={}, customerId={}", orderId, customerId);

        try {
            String reason = cancelReason != null ? cancelReason : "고객 요청";

            String transactionId = IdGenerator.generateCorrelationId();  // transactionId 생성

            boolean cancelled = orderService.cancelOrder(
                    transactionId,      // 1. transactionId
                    orderId,            // 2. orderId
                    customerId,         // 3. customerId
                    reason              // 4. reason
            );

            if (cancelled) {
                log.info("Order cancelled successfully: orderId={}", orderId);
                return ResponseEntity.ok("주문이 취소되었습니다.");
            } else {
                log.warn("Failed to cancel order: orderId={}", orderId);
                return ResponseEntity.badRequest().body(
                        "주문 취소에 실패했습니다. 이미 취소되었거나 취소할 수 없는 상태입니다.");
            }

        } catch (Exception e) {
            log.error("Error cancelling order: orderId={}", orderId, e);
            return ResponseEntity.internalServerError().body("시스템 오류가 발생했습니다.");
        }
    }




}