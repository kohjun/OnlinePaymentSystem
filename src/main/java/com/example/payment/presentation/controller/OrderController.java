package com.example.payment.presentation.controller;

import com.example.payment.application.service.OrderService;
import com.example.payment.domain.model.order.Order;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.presentation.dto.response.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final AuthorizationGuard authorizationGuard;

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrderStatus(@PathVariable String orderId) {
        authorizationGuard.requireOrderAccess(orderId);
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

        authorizationGuard.requireCustomerAccess(customerId);
        List<OrderResponse> orders = orderService.getOrdersByCustomerId(customerId, page, size)
                .stream()
                .map(OrderResponse::from)
                .toList();
        return ResponseEntity.ok(orders);
    }

}
