package com.example.payment.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.payment.dto.OrderRequest;
import com.example.payment.dto.OrderResponse;
import com.example.payment.service.OrderService;
import com.example.payment.util.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주문 처리 API 엔드포인트
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final RateLimiter rateLimiter;

    /**
     * 새 주문 생성 API
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        // 클라이언트 ID 추출
        String clientId = request.getClientId();

        // 속도 제한 적용
        if (!rateLimiter.allowRequest(clientId)) {
            return ResponseEntity.status(429).body(
                    OrderResponse.builder()
                            .status("REJECTED")
                            .message("Too many requests, please try again later")
                            .build()
            );
        }

        // 주문 생성 처리
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.accepted().body(response);
    }

    /**
     * 주문 상태 조회 API
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrderStatus(@PathVariable String orderId) {
        OrderResponse response = orderService.getOrderStatus(orderId);

        if (response == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(response);
    }
}