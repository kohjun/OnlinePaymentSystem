package com.example.payment.application.event.handler;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.application.service.OrderService;
import com.example.payment.application.service.ReservationService;
import com.example.payment.presentation.dto.response.PaymentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 이벤트 리스너 (한정 상품 예약 시스템용)
 * - 결제 완료/실패에 따른 후처리
 */
@Component  
@Slf4j
@RequiredArgsConstructor
public class PaymentEventListener {

    private final OrderService orderService;
    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;

    /**
     * 결제 이벤트 처리 (수동 커밋)
     */
    @KafkaListener(topics = "payment-events", groupId = "payment-post-processing-group")
    public void handlePaymentEvent(PaymentEvent event, Acknowledgment ack) {
        try {
            log.info("Received payment event: type={}, paymentId={}, correlationId={}",
                    event.getEventType(), event.getPayload().getPaymentId(), event.getCorrelationId());

            PaymentResponse payment = event.getPayload();

            switch (event.getEventType()) {
                case PaymentEvent.PAYMENT_PROCESSED:
                    handlePaymentSuccess(payment);
                    break;

                case PaymentEvent.PAYMENT_FAILED:
                    handlePaymentFailure(payment);
                    break;

                case PaymentEvent.PAYMENT_CREATED:
                    // 예약 시스템에서는 특별한 처리 불필요
                    log.debug("Payment created event received: {}", payment.getPaymentId());
                    break;

                default:
                    log.warn("Unknown payment event type: {}", event.getEventType());
            }

            // 메시지 처리 완료 확인
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Error processing payment event: eventType={}, paymentId={}",
                    event.getEventType(), event.getPayload().getPaymentId(), e);

            // 에러 시에도 ACK (DLQ 사용 권장)
            ack.acknowledge();
        }
    }

    /**
     * 결제 성공 후처리
     */
    private void handlePaymentSuccess(PaymentResponse payment) {
        try {
            log.info("Processing payment success: orderId={}, paymentId={}",
                    payment.getOrderId(), payment.getPaymentId());

            // 1. 주문 상태를 결제 완료로 업데이트
            if (payment.getOrderId() != null) {
                boolean updated = orderService.updateOrderStatus(
                        payment.getOrderId(),
                        "PAID",
                        "결제가 완료되었습니다."
                );

                if (updated) {
                    log.info("Order status updated to PAID: orderId={}", payment.getOrderId());
                } else {
                    log.warn("Failed to update order status: orderId={}", payment.getOrderId());
                }
            }

            // 2. 추가 비즈니스 로직 (필요한 경우)
            // - 티켓 발권 시스템 연동
            // - 좌석 배정
            // - 고객 알림 등

        } catch (Exception e) {
            log.error("Error in payment success handling: orderId={}, paymentId={}",
                    payment.getOrderId(), payment.getPaymentId(), e);
        }
    }

    /**
     * 결제 실패 후처리
     */
    private void handlePaymentFailure(PaymentResponse payment) {
        try {
            log.info("Processing payment failure: reservationId={}, paymentId={}",
                    payment.getReservationId(), payment.getPaymentId());

            // 1. 예약 취소 (재고 복원)
            if (payment.getReservationId() != null) {
                // 시스템 차원의 예약 취소 (고객 ID는 임시로 SYSTEM 사용)
                boolean cancelled = reservationService.cancelReservation(
                        payment.getReservationId(),
                        "SYSTEM" // 실제로는 payment에서 customerId를 가져와야 함
                );

                if (cancelled) {
                    log.info("Reservation cancelled due to payment failure: reservationId={}",
                            payment.getReservationId());
                } else {
                    log.error("Failed to cancel reservation: reservationId={}",
                            payment.getReservationId());
                    // 심각한 상황 - 알림 필요
                    alertCriticalIssue("RESERVATION_CANCEL_FAILED", payment);
                }
            }

            // 2. 실패 알림 발송 (고객에게)
            // notificationService.sendPaymentFailureNotification(payment);

        } catch (Exception e) {
            log.error("Error in payment failure handling: reservationId={}, paymentId={}",
                    payment.getReservationId(), payment.getPaymentId(), e);
        }
    }

    /**
     * 심각한 문제 알림
     */
    private void alertCriticalIssue(String issueType, PaymentResponse payment) {
        log.error("CRITICAL ISSUE: {} - PaymentId: {}, ReservationId: {}, OrderId: {}",
                issueType, payment.getPaymentId(), payment.getReservationId(), payment.getOrderId());

        // 실제 구현에서는 모니터링 시스템이나 알림 서비스로 전송
        // alertService.sendCriticalAlert(issueType, payment);
    }
}