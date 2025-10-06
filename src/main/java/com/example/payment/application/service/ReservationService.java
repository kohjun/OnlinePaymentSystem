package com.example.payment.application.service;

import com.example.payment.domain.exception.ReservationException;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.model.reservation.ReservationStatus;
import com.example.payment.infrastructure.lock.DistributedLockService;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.util.ResourceReservationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 재고 예약 서비스 - 단일 책임 원칙 준수
 *
 * 🎯 단일 책임: 재고 선점(Reservation)만 담당
 *
 * 담당 범위:
 * - 재고 예약 (선점)
 * - 예약 취소
 * - 예약 조회
 *
 * 담당하지 않음:
 * - 재고 확정 → InventoryManagementService
 * - WAL 로그 → WalService (횡단 관심사)
 * - 캐싱 → CacheService (인프라)
 * - 분산 락 → DistributedLockService (인프라)
 * - 주문 생성 → OrderService
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationService {

    // 인프라 서비스들 (횡단 관심사)
    private final DistributedLockService lockService;
    private final WalService walService;
    private final CacheService cacheService;
    private final ResourceReservationService redisReservationService;

    // 설정
    private static final int DEFAULT_RESERVATION_TTL_SECONDS = 300; // 5분

    /**
     * 재고 선점 (Phase 1)
     *
     * 프로세스:
     * 1. 분산 락 획득 (동시성 제어)
     * 2. WAL 시작 로그
     * 3. Redis에서 재고 선점
     * 4. 도메인 객체 생성
     * 5. 캐시 저장
     * 6. WAL 완료 로그
     *
     * @param productId 상품 ID
     * @param customerId 고객 ID
     * @param quantity 수량
     * @param clientId 클라이언트 ID
     * @return 예약 도메인 객체 (실패 시 null)
     */
    public InventoryReservation reserveInventory(
            String productId, String customerId, Integer quantity, String clientId) {

        log.info("Starting inventory reservation: productId={}, customerId={}, quantity={}",
                productId, customerId, quantity);

        String reservationId = IdGenerator.generateReservationId();
        String lockKey = "inventory:" + productId;

        // 분산 락으로 동시성 제어
        return lockService.executeWithLock(lockKey, () -> {
            try {
                // 1. WAL 시작 로그 기록
                String walLogId = walService.logOperationStart(
                        "INVENTORY_RESERVE_START",
                        "reservations",
                        buildReservationJson(reservationId, productId, customerId, quantity, "RESERVED")
                );

                // 2. Redis에서 재고 선점 (Lua 스크립트)
                List<Object> redisResult = redisReservationService.reserveResource(
                        lockKey,
                        reservationId,
                        quantity,
                        DEFAULT_RESERVATION_TTL_SECONDS
                );

                boolean success = (Boolean) redisResult.get(0);
                String message = (String) redisResult.get(1);

                if (!success) {
                    log.warn("Inventory reservation failed: productId={}, reason={}",
                            productId, message);

                    // WAL 실패 로그
                    walService.updateLogStatus(walLogId, "FAILED", "재고 부족: " + message);

                    return null;
                }

                // 3. 도메인 객체 생성
                InventoryReservation reservation = InventoryReservation.builder()
                        .reservationId(reservationId)
                        .productId(productId)
                        .customerId(customerId)
                        .quantity(quantity)
                        .status(ReservationStatus.RESERVED)
                        .createdAt(LocalDateTime.now())
                        .expiresAt(LocalDateTime.now().plusMinutes(5))
                        .build();

                // 4. 캐시에 저장
                String cacheKey = "reservation:" + reservationId;
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

                // 5. WAL 완료 로그
                walService.logOperationComplete(
                        "INVENTORY_RESERVE_COMPLETE",
                        "reservations",
                        null,
                        buildReservationJson(reservationId, productId, customerId, quantity, "RESERVED")
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "예약 완료");

                log.info("Inventory reservation succeeded: reservationId={}, productId={}",
                        reservationId, productId);

                return reservation;

            } catch (Exception e) {
                log.error("Error during inventory reservation: productId={}, customerId={}",
                        productId, customerId, e);

                // WAL 실패 로그
                walService.logOperationFailure(
                        "INVENTORY_RESERVE_ERROR",
                        "reservations",
                        e.getMessage()
                );

                throw new ReservationException("재고 선점 실패", e);
            }
        });
    }

    /**
     * 예약 취소
     *
     * @param reservationId 예약 ID
     * @param customerId 고객 ID (권한 확인용)
     * @return 취소 성공 여부
     */
    public boolean cancelReservation(String reservationId, String customerId) {
        try {
            log.info("Cancelling reservation: reservationId={}, customerId={}",
                    reservationId, customerId);

            // 1. 예약 조회 (권한 확인)
            InventoryReservation reservation = getReservation(reservationId);
            if (reservation == null) {
                log.warn("Reservation not found: reservationId={}", reservationId);
                return false;
            }

            if (!