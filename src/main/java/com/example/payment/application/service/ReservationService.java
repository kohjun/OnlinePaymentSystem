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
 * 재고 예약 서비스
 *
 * 재고 선점(Reservation)만 담당
 *
 * 담당 범위:
 * - 재고 예약 (선점)
 * - 예약 취소
 * - 예약 조회
 *
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
     * 4. 도메인 객체 생성c
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

            // 2. 권한 확인
            if (!customerId.equals(reservation.getCustomerId()) && !"SYSTEM".equals(customerId)) {
                log.warn("Customer ID mismatch: reservationId={}, expected={}, actual={}",
                        reservationId, reservation.getCustomerId(), customerId);
                return false;
            }

            // 3. 취소 가능 여부 확인
            if (!reservation.canBeCancelled()) {
                log.warn("Reservation cannot be cancelled: reservationId={}, status={}",
                        reservationId, reservation.getStatus());
                return false;
            }

            // 4. WAL 로그
            String walLogId = walService.logOperationStart(
                    "RESERVATION_CANCEL_START",
                    "reservations",
                    buildReservationJson(reservationId, reservation.getProductId(),
                            customerId, reservation.getQuantity(), "CANCELLED")
            );

            // 5. Redis에서 예약 취소
            boolean cancelled = redisReservationService.cancelReservation(reservationId);

            if (cancelled) {
                // 6. 도메인 상태 업데이트
                reservation.setStatus(ReservationStatus.CANCELLED);

                // 7. 캐시 업데이트
                String cacheKey = "reservation:" + reservationId;
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

                // 8. WAL 완료
                walService.logOperationComplete(
                        "RESERVATION_CANCEL_COMPLETE",
                        "reservations",
                        buildReservationJson(reservationId, reservation.getProductId(),
                                customerId, reservation.getQuantity(), "RESERVED"),
                        buildReservationJson(reservationId, reservation.getProductId(),
                                customerId, reservation.getQuantity(), "CANCELLED")
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "예약 취소 완료");

                log.info("Reservation cancelled: reservationId={}", reservationId);
                return true;

            } else {
                walService.updateLogStatus(walLogId, "FAILED", "Redis 취소 실패");
                log.warn("Failed to cancel reservation in Redis: reservationId={}", reservationId);
                return false;
            }

        } catch (Exception e) {
            log.error("Error cancelling reservation: reservationId={}", reservationId, e);

            walService.logOperationFailure(
                    "RESERVATION_CANCEL_ERROR",
                    "reservations",
                    e.getMessage()
            );

            return false;
        }
    }

    /**
     * 예약 조회
     *
     * @param reservationId 예약 ID
     * @return 예약 도메인 객체 (없으면 null)
     */
    public InventoryReservation getReservation(String reservationId) {
        try {
            String cacheKey = "reservation:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                log.debug("Reservation found in cache: reservationId={}", reservationId);


                if (cachedData instanceof InventoryReservation) {
                    return (InventoryReservation) cachedData;
                } else {
                    log.warn("Cached data is not InventoryReservation type: reservationId={}, actualType={}",
                            reservationId, cachedData.getClass().getName());
                    // 캐시 데이터가 잘못된 경우 삭제
                    cacheService.deleteCache(cacheKey);
                    return null;
                }
            }

            log.debug("Reservation not found: reservationId={}", reservationId);
            return null;

        } catch (Exception e) {
            log.error("Error getting reservation: reservationId={}", reservationId, e);
            return null;
        }
    }

    // ========================================
    // 내부 헬퍼 메서드
    // ========================================

    /**
     * 예약 JSON 생성 (WAL 로그용)
     */
    private String buildReservationJson(String reservationId, String productId,
                                        String customerId, Integer quantity, String status) {
        return String.format(
                "{\"reservationId\":\"%s\",\"productId\":\"%s\",\"customerId\":\"%s\",\"quantity\":%d,\"status\":\"%s\",\"timestamp\":\"%s\"}",
                reservationId, productId, customerId, quantity, status, LocalDateTime.now()
        );
    }
}