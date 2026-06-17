package com.example.payment.presentation.controller;

import com.example.payment.application.service.OrderService;
import com.example.payment.domain.model.order.Order;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.response.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
@Validated
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrderStatus(@PathVariable String orderId) {
        log.debug("Getting order status: orderId={}", orderId);

        Order order = orderService.getOrder(orderId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<OrderResponse>> getCustomerOrders(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        List<OrderResponse> orders = orderService.getOrdersByCustomerId(customerId, page, size)
                .stream()
                .map(OrderResponse::from)
                .toList();
        return ResponseEntity.ok(orders);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<String> cancelOrder(@PathVariable String orderId,
                                              @RequestParam String customerId,
                                              @RequestParam(required = false) String cancelReason) {

        log.info("Order cancellation requested: orderId={}, customerId={}", orderId, customerId);

        try {
            String reason = cancelReason != null ? cancelReason : "Customer requested cancellation";
            String transactionId = IdGenerator.generateCorrelationId();

            boolean cancelled = orderService.cancelOrder(transactionId, orderId, customerId, reason);
            if (cancelled) {
                log.info("Order cancelled successfully: orderId={}", orderId);
                return ResponseEntity.ok("Order cancelled successfully.");
            }

            log.warn("Failed to cancel order: orderId={}", orderId);
            return ResponseEntity.badRequest().body(
                    "Order cancellation failed. It may already be cancelled or cannot be cancelled."
            );

        } catch (Exception e) {
            log.error("Error cancelling order: orderId={}", orderId, e);
            return ResponseEntity.internalServerError().body("System error while cancelling order.");
        }
    }
}
