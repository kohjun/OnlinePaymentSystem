package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.MarketplaceCheckoutRequest;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceCheckoutServiceTest {

    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    private final CompleteReservationGateway completeReservationGateway = mock(CompleteReservationGateway.class);
    private final MarketplaceOrderService marketplaceOrderService = mock(MarketplaceOrderService.class);
    private final CheckoutPricingService checkoutPricingService = mock(CheckoutPricingService.class);

    private final MarketplaceCheckoutService service = new MarketplaceCheckoutService(
            saleEventRepository,
            listingRepository,
            inventoryRepository,
            completeReservationGateway,
            marketplaceOrderService,
            checkoutPricingService
    );

    @Test
    void directDropCheckoutDelegatesToCompleteReservationWithServerPrice() {
        when(saleEventRepository.findById("EVT-DROP")).thenReturn(Optional.of(SaleEvent.builder()
                .saleEventId("EVT-DROP")
                .listingId("LIST-DROP")
                .sellerId("SELLER-1")
                .productId("PROD-DROP")
                .saleType(SaleType.DROP)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now().minusMinutes(5))
                .endsAt(LocalDateTime.now().plusHours(1))
                .price(new BigDecimal("150000"))
                .stockQuantity(10)
                .build()));
        when(listingRepository.findById("LIST-DROP")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-DROP")
                .sellerId("SELLER-1")
                .productId("PROD-DROP")
                .title("Limited Drop")
                .status(ListingStatus.ACTIVE)
                .build()));
        when(inventoryRepository.findById("PROD-DROP")).thenReturn(Optional.of(Inventory.builder()
                .productId("PROD-DROP")
                .totalQuantity(10)
                .availableQuantity(4)
                .reservedQuantity(0)
                .build()));
        when(completeReservationGateway.processCompleteReservation(any()))
                .thenReturn(CompleteReservationResponse.success(
                        "RES-1", "ORD-1", "PAY-1", "TX-1",
                        "PROD-DROP", 1, new BigDecimal("150000"), "KRW"
                ));
        when(checkoutPricingService.applySaleEventPrice(any(), any(), anyBoolean()))
                .thenAnswer(invocation -> {
                    CompleteReservationRequest completeRequest = invocation.getArgument(0);
                    completeRequest.getPaymentInfo().setAmount(new BigDecimal("150000"));
                    completeRequest.getPaymentInfo().setCurrency("KRW");
                    return CheckoutPricingService.PriceSnapshot.builder()
                            .unitPrice(new BigDecimal("150000"))
                            .amount(new BigDecimal("150000"))
                            .currency("KRW")
                            .priceSource("TEST_SALE_EVENT_PRICE")
                            .calculatedAt(LocalDateTime.now())
                            .build();
                });

        MarketplaceCheckoutRequest request = checkoutRequest(new BigDecimal("1"));

        CompleteReservationResponse response = service.checkout("EVT-DROP", request);

        assertEquals("SUCCESS", response.getStatus());

        ArgumentCaptor<CompleteReservationRequest> captor = ArgumentCaptor.forClass(CompleteReservationRequest.class);
        verify(completeReservationGateway).processCompleteReservation(captor.capture());
        assertEquals("PROD-DROP", captor.getValue().getProductId());
        assertEquals(new BigDecimal("150000"), captor.getValue().getPaymentInfo().getAmount());
        assertEquals("SELLER-1", captor.getValue().getPaymentInfo().getMerchantId());
        verify(marketplaceOrderService).recordCheckout(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void raffleCheckoutIsBlockedUntilWinnerFlowExists() {
        when(saleEventRepository.findById("EVT-RAFFLE")).thenReturn(Optional.of(SaleEvent.builder()
                .saleEventId("EVT-RAFFLE")
                .listingId("LIST-RAFFLE")
                .sellerId("SELLER-1")
                .productId("PROD-RAFFLE")
                .saleType(SaleType.RAFFLE)
                .status(SaleEventStatus.LIVE)
                .startsAt(LocalDateTime.now().minusMinutes(5))
                .endsAt(LocalDateTime.now().plusDays(1))
                .price(new BigDecimal("239000"))
                .stockQuantity(10)
                .build()));
        when(listingRepository.findById("LIST-RAFFLE")).thenReturn(Optional.of(MarketplaceListing.builder()
                .listingId("LIST-RAFFLE")
                .sellerId("SELLER-1")
                .productId("PROD-RAFFLE")
                .title("Limited Raffle")
                .status(ListingStatus.ACTIVE)
                .build()));

        MarketplaceCheckoutException ex = assertThrows(MarketplaceCheckoutException.class,
                () -> service.checkout("EVT-RAFFLE", checkoutRequest(new BigDecimal("239000"))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    private MarketplaceCheckoutRequest checkoutRequest(BigDecimal clientAmount) {
        MarketplaceCheckoutRequest request = new MarketplaceCheckoutRequest();
        request.setCustomerId("CUST-1");
        request.setQuantity(1);
        request.setIdempotencyKey("IDEMP-1");
        request.setCorrelationId("CORR-1");
        request.setPaymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                .amount(clientAmount)
                .currency("KRW")
                .paymentMethod("CREDIT_CARD")
                .build());
        return request;
    }
}
