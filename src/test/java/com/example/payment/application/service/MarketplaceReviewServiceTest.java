package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.FulfillmentStatus;
import com.example.payment.domain.model.marketplace.MarketplaceCheckoutType;
import com.example.payment.domain.model.marketplace.MarketplaceOrder;
import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.MarketplaceReview;
import com.example.payment.domain.model.marketplace.ReviewStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.domain.repository.MarketplaceReviewRepository;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CreateMarketplaceReviewRequest;
import com.example.payment.presentation.dto.request.ModerateMarketplaceReviewRequest;
import com.example.payment.presentation.dto.response.MarketplaceReviewResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceReviewServiceTest {

    private final MarketplaceOrderRepository orderRepository = mock(MarketplaceOrderRepository.class);
    private final MarketplaceReviewRepository reviewRepository = mock(MarketplaceReviewRepository.class);

    private final MarketplaceReviewService service = new MarketplaceReviewService(orderRepository, reviewRepository);

    @Test
    void createsSellerReviewForDeliveredBuyerOrder() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("USER"));
        MarketplaceOrder order = deliveredOrder("MORD-1", "CUST-1");
        CreateMarketplaceReviewRequest request = new CreateMarketplaceReviewRequest();
        request.setRating(5);
        request.setComment("  Fast shipping and accurate description.  ");

        when(orderRepository.findById("MORD-1")).thenReturn(Optional.of(order));
        when(reviewRepository.findByMarketplaceOrderIdAndReviewerUserId("MORD-1", "USER-1")).thenReturn(Optional.empty());
        when(reviewRepository.save(any(MarketplaceReview.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceReviewResponse response = service.upsertSellerReview(principal, "MORD-1", request);

        assertEquals("MORD-1", response.getMarketplaceOrderId());
        assertEquals("USER-1", response.getReviewerUserId());
        assertEquals("CUST-1", response.getReviewerCustomerId());
        assertEquals("SELLER-1", response.getTargetSellerId());
        assertEquals(5, response.getRating());
        assertEquals("Fast shipping and accurate description.", response.getComment());
        assertEquals(ReviewStatus.VISIBLE, response.getStatus());
        assertNotNull(response.getReviewId());
    }

    @Test
    void updatesExistingReviewForSameOrderAndReviewer() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("USER"));
        MarketplaceReview existing = MarketplaceReview.builder()
                .reviewId("REV-1")
                .marketplaceOrderId("MORD-1")
                .reviewerUserId("USER-1")
                .reviewerCustomerId("CUST-1")
                .targetSellerId("SELLER-1")
                .rating(3)
                .comment("Initial")
                .status(ReviewStatus.VISIBLE)
                .createdAt(LocalDateTime.now())
                .build();
        CreateMarketplaceReviewRequest request = new CreateMarketplaceReviewRequest();
        request.setRating(4);
        request.setComment("Updated after seller follow-up.");

        when(orderRepository.findById("MORD-1")).thenReturn(Optional.of(deliveredOrder("MORD-1", "CUST-1")));
        when(reviewRepository.findByMarketplaceOrderIdAndReviewerUserId("MORD-1", "USER-1")).thenReturn(Optional.of(existing));
        when(reviewRepository.save(any(MarketplaceReview.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceReviewResponse response = service.upsertSellerReview(principal, "MORD-1", request);

        assertEquals("REV-1", response.getReviewId());
        assertEquals(4, response.getRating());
        assertEquals("Updated after seller follow-up.", response.getComment());
    }

    @Test
    void rejectsReviewBeforeDelivery() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("USER"));
        MarketplaceOrder order = deliveredOrder("MORD-1", "CUST-1");
        order.setFulfillmentStatus(FulfillmentStatus.SHIPPED);
        CreateMarketplaceReviewRequest request = new CreateMarketplaceReviewRequest();
        request.setRating(5);

        when(orderRepository.findById("MORD-1")).thenReturn(Optional.of(order));

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertSellerReview(principal, "MORD-1", request));
        verify(reviewRepository, never()).save(any(MarketplaceReview.class));
    }

    @Test
    void rejectsReviewFromDifferentBuyer() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-2", "CUST-2", null, Set.of("USER"));
        CreateMarketplaceReviewRequest request = new CreateMarketplaceReviewRequest();
        request.setRating(5);

        when(orderRepository.findById("MORD-1")).thenReturn(Optional.of(deliveredOrder("MORD-1", "CUST-1")));

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertSellerReview(principal, "MORD-1", request));
        verify(reviewRepository, never()).save(any(MarketplaceReview.class));
    }

    @Test
    void moderatesReviewVisibility() {
        MarketplaceReview review = MarketplaceReview.builder()
                .reviewId("REV-1")
                .marketplaceOrderId("MORD-1")
                .reviewerUserId("USER-1")
                .reviewerCustomerId("CUST-1")
                .targetSellerId("SELLER-1")
                .rating(1)
                .comment("Contains personal information.")
                .status(ReviewStatus.VISIBLE)
                .createdAt(LocalDateTime.now())
                .build();
        ModerateMarketplaceReviewRequest request = new ModerateMarketplaceReviewRequest();
        request.setStatus(ReviewStatus.HIDDEN);
        request.setNote("Privacy issue.");

        when(reviewRepository.findById("REV-1")).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(MarketplaceReview.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MarketplaceReviewResponse response = service.moderateReview("ops-1", "REV-1", request);

        assertEquals(ReviewStatus.HIDDEN, response.getStatus());
        assertEquals("ops-1", response.getModeratedBy());
        assertEquals("Privacy issue.", response.getModerationNote());
    }

    private MarketplaceOrder deliveredOrder(String orderId, String customerId) {
        return MarketplaceOrder.builder()
                .marketplaceOrderId(orderId)
                .saleEventId("EVT-1")
                .listingId("LIST-1")
                .sellerId("SELLER-1")
                .customerId(customerId)
                .saleType(SaleType.FIXED_PRICE)
                .checkoutType(MarketplaceCheckoutType.DIRECT)
                .status(MarketplaceOrderStatus.PAID)
                .fulfillmentStatus(FulfillmentStatus.DELIVERED)
                .productId("PROD-1")
                .quantity(1)
                .amount(new BigDecimal("10000"))
                .currency("KRW")
                .createdAt(LocalDateTime.now())
                .fulfilledAt(LocalDateTime.now())
                .build();
    }
}
