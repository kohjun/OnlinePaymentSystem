/**
 * ========================================
 * 4. InventoryManagementService (정합성 관리 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.inventory.InventoryConfirmation;
import com.example.payment.domain.model.inventory.Reservation;
import com.example.payment.domain.repository.InventoryRepository;

import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;


@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryManagementService {

    private final ResourceReservationService redisReservationService;
    private final InventoryRepository inventoryRepository;

    /**
     * 예약 확정 - 도메인 객체만 반환
     */
    public InventoryConfirmation confirmReservation(String reservationId, String orderId, String paymentId) {

        log.info("Confirming inventory reservation: reservationId={}, orderId={}, paymentId={}",
                reservationId, orderId, paymentId);

        try {
            // Redis에서 예약 확정
            boolean redisConfirmed = redisReservationService.confirmReservation(reservationId);

            if (!redisConfirmed) {
                return InventoryConfirmation.builder()
                        .reservationId(reservationId)
                        .orderId(orderId)
                        .paymentId(paymentId)
                        .confirmed(false)
                        .reason("Redis 예약 확정 실패")
                        .confirmedAt(LocalDateTime.now())
                        .build();
            }

            // 성공 시 확정 객체 반환
            InventoryConfirmation confirmation = InventoryConfirmation.builder()
                    .reservationId(reservationId)
                    .orderId(orderId)
                    .paymentId(paymentId)
                    .confirmed(true)
                    .reason("확정 완료")
                    .confirmedAt(LocalDateTime.now())
                    .build();

            log.info("Inventory reservation confirmed: reservationId={}", reservationId);
            return confirmation;

        } catch (Exception e) {
            log.error("Error confirming reservation: reservationId={}", reservationId, e);

            return InventoryConfirmation.builder()
                    .reservationId(reservationId)
                    .orderId(orderId)
                    .paymentId(paymentId)
                    .confirmed(false)
                    .reason("확정 처리 중 오류: " + e.getMessage())
                    .confirmedAt(LocalDateTime.now())
                    .build();
        }
    }
}