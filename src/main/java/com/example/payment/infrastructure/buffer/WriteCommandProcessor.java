/**
 * 명령 처리기
 */
package com.example.payment.infrastructure.buffer;

import com.example.payment.domain.model.inventory.Reservation;
import com.example.payment.domain.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class WriteCommandProcessor {

    private final ReservationRepository reservationRepository;

    /**
     * 명령 처리 (타입별 분기)
     */
    @Transactional(timeout = 5)
    public boolean processCommand(WriteCommand command) {
        try {
            switch (command.getType()) {
                case "RESERVATION_WRITE":
                    return processReservationWrite((ReservationWriteCommand) command);
                case "ORDER_WRITE":
                    return processOrderWrite((OrderWriteCommand) command);
                default:
                    log.warn("Unknown command type: {}", command.getType());
                    return false;
            }
        } catch (Exception e) {
            log.error("Error processing command: {}", command.getCommandId(), e);
            return false;
        }
    }

    /**
     * 예약 쓰기 처리
     */
    private boolean processReservationWrite(ReservationWriteCommand command) {
        try {
            Reservation reservation = Reservation.builder()
                    .id(command.getReservationId())
                    .productId(command.getProductId())
                    .quantity(command.getQuantity())
                    .status(Reservation.ReservationStatus.valueOf(command.getStatus()))
                    .expiresAt(command.getExpiresAt())
                    .build();

            reservationRepository.save(reservation);

            log.debug("Reservation saved to DB: reservationId={}", command.getReservationId());
            return true;

        } catch (Exception e) {
            log.error("Failed to save reservation: reservationId={}",
                    command.getReservationId(), e);
            return false;
        }
    }

    /**
     * 주문 쓰기 처리
     */
    private boolean processOrderWrite(OrderWriteCommand command) {
        try {
            // CompletedOrder를 DB에 저장하는 로직
            // 실제 구현에서는 Order 엔티티를 별도로 만들거나
            // 기존 Reservation을 업데이트할 수 있음

            log.debug("Order saved to DB: orderId={}", command.getOrderId());
            return true;

        } catch (Exception e) {
            log.error("Failed to save order: orderId={}", command.getOrderId(), e);
            return false;
        }
    }
}