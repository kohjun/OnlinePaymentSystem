package com.example.payment.infrastructure.config;

import com.example.payment.application.service.MarketplaceRealtimeEnvelope;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.model.common.Money;
import com.example.payment.domain.model.marketplace.AuctionBidStatus;
import com.example.payment.domain.model.marketplace.RaffleEntryStatus;
import com.example.payment.domain.model.order.Order;
import com.example.payment.domain.model.order.OrderStatus;
import com.example.payment.domain.model.payment.Payment;
import com.example.payment.domain.model.payment.PaymentMethod;
import com.example.payment.domain.model.payment.PaymentStatus;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.model.reservation.ReservationStatus;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.response.AuctionBidResponse;
import com.example.payment.presentation.dto.response.CompleteReservationResponse;
import com.example.payment.presentation.dto.response.PaymentResponse;
import com.example.payment.presentation.dto.response.RaffleEntryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장되거나 다른 프로세스로 나가는 JSON의 기준 샘플.
 *
 * 값은 고정이다. src/test/resources/wire-format의 기준 파일은 이 샘플을 Jackson 2
 * 시절 매퍼로 쓴 결과이므로, 샘플을 바꾸면 기준 파일과 비교하는 의미가 사라진다.
 */
final class WireFormatSamples {

    static final LocalDateTime AT = LocalDateTime.of(2026, 10, 25, 1, 30, 15);
    static final LocalDateTime LATER = AT.plusMinutes(10);
    static final BigDecimal AMOUNT = new BigDecimal("150000.00");

    private WireFormatSamples() {
    }

    /** DB 멱등 기록·토스 인텐트의 response_body, 예약 API 응답. */
    static CompleteReservationResponse completeReservationResponse() {
        return CompleteReservationResponse.builder()
                .status("SUCCESS")
                .message("결제가 완료되었습니다.")
                .timestamp(AT)
                .correlationId("CORR-1")
                .workflowId("complete-reservation-1")
                .phase("COMPLETED")
                .retryAfterSeconds(3)
                .reservation(CompleteReservationResponse.ReservationInfo.builder()
                        .reservationId("RES-1")
                        .productId("PROD-1")
                        .quantity(2)
                        .expiresAt(LATER)
                        .build())
                .order(CompleteReservationResponse.OrderInfo.builder()
                        .orderId("ORD-1")
                        .customerId("CUST-1")
                        .status("PAID")
                        .createdAt(AT)
                        .build())
                .payment(CompleteReservationResponse.PaymentInfo.builder()
                        .paymentId("PAY-1")
                        .transactionId("TX-1")
                        .approvalNumber("APPROVAL-1")
                        .amount(AMOUNT)
                        .currency("KRW")
                        .status("APPROVED")
                        .processedAt(AT)
                        .build())
                .build();
    }

    /** 입찰 멱등 캐시(Redis 문자열)와 입찰 API 응답. */
    static AuctionBidResponse auctionBidResponse() {
        return AuctionBidResponse.builder()
                .bidId("BID-1")
                .saleEventId("EVT-1")
                .customerId("CUST-1")
                .bidAmount(AMOUNT)
                .status(AuctionBidStatus.values()[0])
                .createdAt(AT)
                .build();
    }

    /** 응모 멱등 캐시(Redis 문자열)와 응모 API 응답. */
    static RaffleEntryResponse raffleEntryResponse() {
        return RaffleEntryResponse.builder()
                .entryId("ENTRY-1")
                .saleEventId("EVT-1")
                .customerId("CUST-1")
                .status(RaffleEntryStatus.values()[0])
                .createdAt(AT)
                .build();
    }

    /** payment-events 토픽 메시지. */
    static PaymentEvent paymentEvent() {
        PaymentResponse payload = PaymentResponse.builder()
                .status("SUCCESS")
                .timestamp(AT)
                .paymentId("PAY-1")
                .orderId("ORD-1")
                .reservationId("RES-1")
                .amount(AMOUNT)
                .currency("KRW")
                .transactionId("TX-1")
                .approvalNumber("APPROVAL-1")
                .gatewayName("TOSS")
                .processedAt(AT)
                .build();
        PaymentEvent event = new PaymentEvent(PaymentEvent.PAYMENT_PROCESSED, payload, "CORR-1");
        event.setEventId("EVENT-1");
        event.setTimestamp(AT);
        return event;
    }

    /** OutboxEventService가 outbox_events.payload에 쓰는 봉투와 같은 모양. */
    static Map<String, Object> outboxEnvelope() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", "PROD-1");
        item.put("quantity", 2);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", "ORD-1");
        payload.put("amount", AMOUNT);
        payload.put("quantity", 2);
        payload.put("sequence", 9_007_199_254_740_993L);
        payload.put("refundable", true);
        payload.put("paidAt", AT);
        payload.put("status", OrderStatus.PAID);
        payload.put("items", List.of(item));
        payload.put("reason", null);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", "EVENT-1");
        envelope.put("eventType", "ORDER_PAID");
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateType", "ORDER");
        envelope.put("aggregateId", "ORD-1");
        envelope.put("occurredAt", AT.toString());
        envelope.put("payload", payload);
        return envelope;
    }

    /** 멱등 요청 지문(request_hash)의 입력. */
    static CompleteReservationRequest completeReservationRequest() {
        return CompleteReservationRequest.builder()
                .productId("PROD-1")
                .customerId("CUST-1")
                .quantity(2)
                .clientId("web")
                .idempotencyKey("IDEMP-1")
                .paymentInfo(CompleteReservationRequest.PaymentInfo.builder()
                        .amount(AMOUNT)
                        .currency("KRW")
                        .paymentMethod("CARD")
                        .merchantId("MERCHANT-1")
                        .build())
                .build();
    }

    /** CacheService가 Redis 매퍼로 캐시하는 주문. */
    static Order order() {
        Order order = Order.builder()
                .orderId("ORD-1")
                .customerId("CUST-1")
                .productId("PROD-1")
                .quantity(2)
                .amount(Money.of(AMOUNT, "KRW"))
                .reservationId("RES-1")
                .paymentId("PAY-1")
                .currency("KRW")
                .status(OrderStatus.PAID)
                .createdAt(AT)
                .updatedAt(LATER)
                .build();
        order.setCreatedBy("system");
        return order;
    }

    /** CacheService가 Redis 매퍼로 캐시하는 결제. */
    static Payment payment() {
        Payment payment = Payment.builder()
                .paymentId("PAY-1")
                .orderId("ORD-1")
                .reservationId("RES-1")
                .customerId("CUST-1")
                .amount(Money.of(AMOUNT, "KRW"))
                .method(PaymentMethod.CREDIT_CARD)
                .status(PaymentStatus.values()[0])
                .transactionId("TX-1")
                .failureReason("none")
                .processedAt(AT)
                .approvalNumber("APPROVAL-1")
                .gatewayName("TOSS")
                .build();
        payment.setCreatedAt(AT);
        payment.setUpdatedAt(LATER);
        return payment;
    }

    /** CacheService가 Redis 매퍼로 캐시하는 재고 예약. */
    static InventoryReservation inventoryReservation() {
        return InventoryReservation.builder()
                .reservationId("RES-1")
                .productId("PROD-1")
                .customerId("CUST-1")
                .quantity(2)
                .status(ReservationStatus.values()[0])
                .createdAt(AT)
                .expiresAt(LATER)
                .build();
    }

    /** 인스턴스 사이 실시간 중계 메시지(Redis Pub/Sub). */
    static MarketplaceRealtimeEnvelope realtimeEnvelope() {
        return new MarketplaceRealtimeEnvelope(
                "instance-a",
                "SALE_EVENT",
                "EVENT-1",
                "auction.bid",
                auctionBidResponse(),
                Instant.parse("2026-10-25T01:30:15Z"));
    }
}
