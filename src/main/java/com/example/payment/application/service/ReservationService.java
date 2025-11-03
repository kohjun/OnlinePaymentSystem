package com.example.payment.application.service;

import com.example.payment.domain.exception.ReservationException;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.model.reservation.ReservationStatus;
import com.example.payment.infrastructure.lock.DistributedLockService;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
import com.example.payment.infrastructure.persistence.wal.WalService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.util.ResourceReservationService;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ✅ 개선된 재고 예약 서비스
 *
 * 주요 개선사항:
 * 1. 트랜잭션 ID를 외부에서 주입받아 WAL 일관성 보장
 * 2. 엔티티 ID(reservationId) 추적 강화
 * 3. WAL 로그에 엔티티 메타데이터 포함
 * 4. [수정] reserveInventory가 walLogId를 반환하도록 변경 (문제 2.B 해결)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationService {

    private final DistributedLockService lockService;
    private final WalService walService;
    private final CacheService cacheService;
    private final ResourceReservationService redisReservationService;

    private static final int DEFAULT_RESERVATION_TTL_SECONDS = 300; // 5분

    /**
     * [추가] 1. 반환용 DTO 내부 클래스 생성
     * 재고 예약(Phase 1)의 결과물과 해당 작업의 WAL 로그 ID를 함께 반환합니다.
     */
    @Data
    @AllArgsConstructor
    public static class ReservationResult {
        private InventoryReservation reservation;
        private String walLogId;
    }

    /**
     * ✅ 개선: 재고 선점 (Phase 1) - 트랜잭션 ID 주입
     * [수정] 2. 반환 타입을 ReservationResult로 변경
     *
     * @param transactionId 비즈니스 트랜잭션 ID (correlationId)
     * @param productId 상품 ID
     * @param customerId 고객 ID
     * @param quantity 수량
     * @param clientId 클라이언트 ID
     * @return ReservationResult (예약 도메인 객체 + WAL 로그 ID)
     */
    public ReservationResult reserveInventory( // 2. 반환 타입 변경
                                               String transactionId,  // ✅ 트랜잭션 ID 추가
                                               String productId,
                                               String customerId,
                                               Integer quantity,
                                               String clientId) {

        log.info("[Phase 1] Starting inventory reservation: txId={}, productId={}, customerId={}, quantity={}",
                transactionId, productId, customerId, quantity);

        String reservationId = IdGenerator.generateReservationId();
        String lockKey = "inventory:" + productId;

        // 분산 락으로 동시성 제어
        return lockService.executeWithLock(lockKey, () -> {
            String walLogId = null; // 3. walLogId 변수 선언
            try {
                // ===================================
                // 1. WAL Phase 1 시작 로그 기록
                // ===================================
                String entityIds = buildEntityIdsJson(reservationId, null, null);
                String afterData = buildReservationJson(
                        reservationId, productId, customerId, quantity, "RESERVED"
                );

                walLogId = walService.logOperationStart( // 4. walLogId 할당
                        transactionId,
                        "INVENTORY_RESERVE_START",
                        "reservations",
                        entityIds,
                        afterData
                );

                log.debug("WAL Phase 1 logged: txId={}, walLogId={}, reservationId={}",
                        transactionId, walLogId, reservationId);

                // ===================================
                // 2. Redis에서 재고 선점 (Lua 스크립트)
                // ===================================
                boolean success = redisReservationService.reserveResource(
                        lockKey,
                        quantity,
                        Duration.ofSeconds(DEFAULT_RESERVATION_TTL_SECONDS)
                );

                // [FIX 2] redisResult 파싱 로직을 제거하고 success 변수를 바로 사용합니다.
                if (!success) {
                    String message = "Redis 재고 선점 실패"; // message 변수가 없으므로 대체
                    log.warn("Inventory reservation failed: txId={}, productId={}, reason={}",
                            transactionId, productId, message);

                    // WAL 실패 로그
                    walService.updateLogStatus(walLogId, "FAILED", "재고 부족: " + message);

                    walService.logOperationFailure(
                            transactionId,
                            "INVENTORY_RESERVE_FAILED",
                            "reservations",
                            entityIds,
                            message
                    );

                    return null; // 5. 실패 시 null 반환
                }

                // ===================================
                // 3. 도메인 객체 생성
                // ===================================
                InventoryReservation reservation = InventoryReservation.builder()
                        .reservationId(reservationId)
                        .productId(productId)
                        .customerId(customerId)
                        .quantity(quantity)
                        .status(ReservationStatus.RESERVED)
                        .createdAt(LocalDateTime.now())
                        .expiresAt(LocalDateTime.now().plusMinutes(5))
                        .build();

                // ===================================
                // 4. 캐시에 저장 (메타데이터 포함)
                // ===================================
                String cacheKey = "reservation:" + reservationId;
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

                // 트랜잭션 ID 매핑 캐시 (복구 시 활용)
                String txMappingKey = "tx_reservation:" + transactionId;
                cacheService.cacheData(txMappingKey, reservationId, DEFAULT_RESERVATION_TTL_SECONDS);

                // ===================================
                // 5. WAL Phase 1 완료 로그
                // ===================================
                walService.logOperationComplete(
                        transactionId,
                        "INVENTORY_RESERVE_COMPLETE",
                        "reservations",
                        entityIds,
                        null,
                        afterData
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "예약 완료");

                log.info("[Phase 1] Inventory reservation succeeded: txId={}, reservationId={}, productId={}",
                        transactionId, reservationId, productId);

                return new ReservationResult(reservation, walLogId); // 6. ReservationResult 반환

            } catch (Exception e) {
                log.error("[Phase 1] Error during inventory reservation: txId={}, productId={}, customerId={}",
                        transactionId, productId, customerId, e);

                // 7. 예외 발생 시에도 WAL 기록
                if (walLogId != null) {
                    walService.updateLogStatus(walLogId, "FAILED", e.getMessage());
                }
                String entityIds = buildEntityIdsJson(reservationId, null, null);
                walService.logOperationFailure(
                        transactionId,
                        "INVENTORY_RESERVE_ERROR",
                        "reservations",
                        entityIds,
                        e.getMessage()
                );

                throw new ReservationException("재고 선점 실패", e);
            }
        });
    }

    /**
     * 개선: 예약 취소 - 트랜잭션 ID 주입
     */
    public boolean cancelReservation(String transactionId, String reservationId, String customerId) {
        try {
            log.info("🟠 Cancelling reservation: txId={}, reservationId={}, customerId={}",
                    transactionId, reservationId, customerId);

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
            String entityIds = buildEntityIdsJson(reservationId, null, null);
            String afterData = buildReservationJson(
                    reservationId,
                    reservation.getProductId(),
                    customerId,
                    reservation.getQuantity(),
                    "CANCELLED"
            );

            String walLogId = walService.logOperationStart(
                    transactionId,
                    "RESERVATION_CANCEL_START",
                    "reservations",
                    entityIds,
                    afterData
            );

            // 5. Redis에서 예약 취소
            String resourceKey = "inventory:" + reservation.getProductId();
            int quantityToRelease = reservation.getQuantity();

            // (참고) 이 호출이 성공하려면 ResourceReservationService.releaseResource에
            // reservationId 인자가 추가되어야 합니다. (이전 분석의 '오류 2' 항목)
            boolean cancelled = redisReservationService.releaseResource(resourceKey, quantityToRelease,reservation.getReservationId()); //

            if (cancelled) {
                // 6. 도메인 상태 업데이트
                reservation.setStatus(ReservationStatus.CANCELLED);

                // 7. 캐시 업데이트
                String cacheKey = "reservation:" + reservationId;
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

                // 8. WAL 완료
                String beforeData = buildReservationJson(
                        reservationId,
                        reservation.getProductId(),
                        customerId,
                        reservation.getQuantity(),
                        "RESERVED"
                );

                walService.logOperationComplete(
                        transactionId,
                        "RESERVATION_CANCEL_COMPLETE",
                        "reservations",
                        entityIds,
                        beforeData,
                        afterData
                );
                walService.updateLogStatus(walLogId, "COMMITTED", "예약 취소 완료");

                log.info("Reservation cancelled: txId={}, reservationId={}",
                        transactionId, reservationId);
                return true;

            } else {
                walService.updateLogStatus(walLogId, "FAILED", "Redis 취소 실패");

                walService.logOperationFailure(
                        transactionId,
                        "RESERVATION_CANCEL_FAILED",
                        "reservations",
                        entityIds,
                        "Redis 취소 실패"
                );

                log.warn("Failed to cancel reservation in Redis: reservationId={}", reservationId);
                return false;
            }

        } catch (Exception e) {
            log.error("Error cancelling reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);

            String entityIds = buildEntityIdsJson(reservationId, null, null);
            walService.logOperationFailure(
                    transactionId,
                    "RESERVATION_CANCEL_ERROR",
                    "reservations",
                    entityIds,
                    e.getMessage()
            );

            return false;
        }
    }

    /**
     * 예약 조회
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
                    cacheService.deleteCache(cacheKey);
                }
            }

            log.debug("Reservation not found: reservationId={}", reservationId);
            return null;

        } catch (Exception e) {
            log.error("Error getting reservation: reservationId={}", reservationId, e);
            return null;
        }
    }
    /**
     * ✅ [NEW] 예약 상태를 'CONFIRMED'로 업데이트하고 캐시를 갱신
     * InventoryManagementService에서 호출
     */
    public void confirmReservationStatus(InventoryReservation reservation) {
        try {
            if (reservation == null) {
                log.warn("Cannot confirm status for null reservation.");
                return;
            }

            reservation.setStatus(ReservationStatus.CONFIRMED);

            // 캐시 업데이트 (TTL은 기존과 동일하게)
            String cacheKey = "reservation:" + reservation.getReservationId();
            cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

            log.info("Reservation status set to CONFIRMED and re-cached: {}", reservation.getReservationId());

        } catch (Exception e) {
            log.error("Error updating reservation status to CONFIRMED: {}", reservation.getReservationId(), e);
            // 여기서 예외를 던지면 confirmReservation의 트랜잭션이 롤백될 수 있으나,
            // 이 작업은 보조적이므로 에러 로그만 남깁니다.
        }
    }
    // ===================================
    // Helper Methods - 엔티티 ID 추적용 JSON 빌더
    // ===================================

    /**
     * 엔티티 ID들을 JSON 형태로 구성
     * WAL 로그의 beforeData 필드에 저장하여 데이터 추적 가능
     */
    private String buildEntityIdsJson(String reservationId, String orderId, String paymentId) {
        return String.format(
                "{\"reservationId\":\"%s\",\"orderId\":\"%s\",\"paymentId\":\"%s\"}",
                reservationId != null ? reservationId : "null",
                orderId != null ? orderId : "null",
                paymentId != null ? paymentId : "null"
        );
    }

    private String buildReservationJson(String reservationId, String productId,
                                        String customerId, Integer quantity, String status) {
        return String.format(
                "{\"reservationId\":\"%s\",\"productId\":\"%s\",\"customerId\":\"%s\"," +
                        "\"quantity\":%d,\"status\":\"%s\",\"timestamp\":\"%s\"}",
                reservationId, productId, customerId, quantity, status, LocalDateTime.now()
        );
    }
}