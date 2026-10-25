package com.example.payment.presentation.controller;

import com.example.payment.application.service.CheckoutPricingService;
import com.example.payment.application.service.CompleteReservationGateway;
import com.example.payment.application.service.ReservationOrchestrator;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.infrastructure.util.RateLimiter;
import com.example.payment.presentation.dto.request.ReservationOnlyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReservationControllerExposureTest {

    @Mock
    private ReservationOrchestrator reservationOrchestrator;
    @Mock
    private CompleteReservationGateway completeReservationGateway;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private CheckoutPricingService checkoutPricingService;
    @Mock
    private AuthorizationGuard authorizationGuard;

    @InjectMocks
    private ReservationController controller;

    @BeforeEach
    void disableLegacyMutationApi() {
        ReflectionTestUtils.setField(controller, "legacyReservationApiEnabled", false);
    }

    @Test
    void directInventoryReservationIsGoneBeforeIdentityOrInventoryIsTouched() {
        ReservationOnlyRequest request = ReservationOnlyRequest.builder()
                .productId("PRODUCT-1")
                .customerId("spoofed-customer")
                .quantity(1)
                .clientId("legacy-client")
                .build();

        var response = controller.reserveInventory(request);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals("DIRECT_RESERVATION_DISABLED", response.getBody().getErrorCode());
        verifyNoInteractions(authorizationGuard, reservationOrchestrator, rateLimiter);
    }

    @Test
    void directReservationCancellationIsGoneBeforeOwnershipLookup() {
        var response = controller.cancelReservation("RESERVATION-1", "spoofed-customer", "cancel");

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        verifyNoInteractions(authorizationGuard, reservationOrchestrator);
    }
}
