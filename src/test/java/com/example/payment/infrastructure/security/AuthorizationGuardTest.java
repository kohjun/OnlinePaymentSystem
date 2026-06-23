package com.example.payment.infrastructure.security;

import com.example.payment.domain.entity.OrderRecord;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.OrderRecordRepository;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationGuardTest {

    private PaymentRecordRepository paymentRecordRepository;
    private InventoryReservationRecordRepository reservationRepository;
    private OrderRecordRepository orderRecordRepository;
    private TossPaymentIntentRepository tossPaymentIntentRepository;
    private SecurityAuditService securityAuditService;
    private AuthorizationGuard authorizationGuard;

    @BeforeEach
    void setUp() {
        paymentRecordRepository = mock(PaymentRecordRepository.class);
        reservationRepository = mock(InventoryReservationRecordRepository.class);
        orderRecordRepository = mock(OrderRecordRepository.class);
        tossPaymentIntentRepository = mock(TossPaymentIntentRepository.class);
        securityAuditService = mock(SecurityAuditService.class);
        authorizationGuard = new AuthorizationGuard(
                paymentRecordRepository,
                reservationRepository,
                orderRecordRepository,
                tossPaymentIntentRepository,
                securityAuditService
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requireOrderAccess_allowsOwningCustomer() {
        when(orderRecordRepository.findById("ORD-1")).thenReturn(Optional.of(order("ORD-1", "CUS-1")));
        authenticate("CUS-1", "CUSTOMER");

        assertDoesNotThrow(() -> authorizationGuard.requireOrderAccess("ORD-1"));
    }

    @Test
    void requireOrderAccess_rejectsDifferentCustomer() {
        when(orderRecordRepository.findById("ORD-1")).thenReturn(Optional.of(order("ORD-1", "CUS-OWNER")));
        authenticate("CUS-OTHER", "CUSTOMER");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authorizationGuard.requireOrderAccess("ORD-1")
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(securityAuditService).recordDenied(
                "CUSTOMER_ACCESS",
                "CUSTOMER",
                "CUS-OWNER",
                "Customer ownership mismatch."
        );
    }

    @Test
    void requireOrderAccess_allowsAdminWithoutLeakingOrderOwnership() {
        authenticate("ADMIN-1", "ADMIN");

        assertDoesNotThrow(() -> authorizationGuard.requireOrderAccess("ORD-1"));
        verify(orderRecordRepository, never()).findById("ORD-1");
    }


    @Test
    void requireCustomerAccess_allowsJwtCustomerIdClaim() {
        authenticateJwt(Map.of("sub", "subject-1", "customer_id", "CUS-1"), "ROLE_CUSTOMER");

        assertDoesNotThrow(() -> authorizationGuard.requireCustomerAccess("CUS-1"));
    }

    @Test
    void requireSellerAccess_allowsJwtSellerIdClaim() {
        authenticateJwt(Map.of("sub", "subject-1", "seller_id", "SELLER-1"), "ROLE_SELLER");

        assertDoesNotThrow(() -> authorizationGuard.requireSellerAccess("SELLER-1"));
    }

    @Test
    void requireAdmin_allowsJwtAdminRole() {
        authenticateJwt(Map.of("sub", "admin-1", "customer_id", "ADMIN-1"), "ROLE_ADMIN");

        assertDoesNotThrow(() -> authorizationGuard.requireAdmin());
    }

    private void authenticate(String customerId, String... roles) {
        EverySalePrincipal principal = new EverySalePrincipal(customerId, null, Set.of(roles));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                "test",
                Arrays.stream(roles)
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }


    private void authenticateJwt(Map<String, Object> claims, String... authorities) {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                claims
        );
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt,
                Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList(),
                claims.getOrDefault("customer_id", claims.getOrDefault("sub", "subject")).toString()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private OrderRecord order(String orderId, String customerId) {
        return OrderRecord.builder()
                .orderId(orderId)
                .customerId(customerId)
                .productId("PROD-1")
                .reservationId("RES-1")
                .quantity(1)
                .amount(new BigDecimal("100.00"))
                .currency("KRW")
                .status("PAID")
                .createdAt(LocalDateTime.now())
                .build();
    }
}