/**
 * ========================================
 * 1. ReservationService (재고 선점 전담) - WAL 버전
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.entity.WalLogEntry;
import com.example.payment.domain.exception.ReservationException;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.model.reservation.ReservationStatus;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.infrastructure.persistence.wal.WalLogRepository;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationService {

    private final ResourceReservationService redisReservationService;
    private final WalLogRepository walLogRepository; // WriteBufferService 대신 WAL 사용
    private final CacheService cacheService;

    /**
     * 재고 선점 - WAL 2단계 방식
     */
    public InventoryReservation reserveInventory(String productId, String customerId,
                                                 Integer quantity, String clientId) {

        log.info("Reserving inventory with WAL: productId={}, customerId={}, quantity={}",
                productId, customerId, quantity);

        String transactionId = IdGenerator.generateTransactionId();
        String reservationId = IdGenerator.generateReservationId();

        try {
            // ========================================
            // Phase 1: WAL 기록 + Redis 재고 선점
            // ========================================

            // 1-1. WAL 시작 로그 기록
            WalLogEntry startLog = createReservationStartLog(transactionId, reservationId, productId, customerId, quantity);
            walLogRepository.writeLog(startLog);

            // 1-2. Redis 원자적 재고 선점
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);
            List<Object> result = redisReservationService.reserveResource(
                    "inventory:" + productId,
                    reservationId,
                    quantity,
                    300 // 5분
            );

            boolean success = (Boolean) result.get(0);

            if (!success) {
                String message = (String) result.get(1);
                log.warn("Redis inventory reservation failed: productId={}, reason={}", productId, message);

                // WAL 실패 로그 기록
                walLogRepository.updateLogStatus(startLog.getLogId(), "FAILED", "Redis 재고 선점 실패: " + message);
                return null;
            }

            // 1-3. 도메인 객체 생성
            InventoryReservation reservation = InventoryReservation.builder()
                    .reservationId(reservationId)
                    .productId(productId)
                    .customerId(customerId)
                    .quantity(quantity)
                    .status(ReservationStatus.RESERVED)
                    .createdAt(LocalDateTime.now())
                    .expiresAt(expiresAt)
                    .build();

            // 1-4. Write-Through: 즉시 캐시 저장
            String cacheKey = "reservation:" + reservationId;
            cacheService.cacheData(cacheKey, reservation, 300);

            // 1-5. WAL 완료 로그 기록 (Phase 1 성공)
            WalLogEntry completeLog = createReservationCompleteLog(transactionId, reservationId, reservation);
            walLogRepository.writeLog(completeLog);
            walLogRepository.updateLogStatus(startLog.getLogId(), "COMMITTED", "Phase 1 완료: Redis 재고 선점 성공");

            log.info("Inventory reserved successfully with WAL: reservationId={}, transactionId={}",
                    reservationId, transactionId);

            return reservation;

        } catch (Exception e) {
            log.error("Error reserving inventory with WAL: productId={}, customerId={}, transactionId={}",
                    productId, customerId, transactionId, e);

            // WAL 에러 로그 기록
            try {
                walLogRepository.writeLog(createReservationErrorLog(transactionId, reservationId, e.getMessage()));
            } catch (Exception walError) {
                log.error("Failed to write WAL error log", walError);
            }

            throw new ReservationException("재고 선점 중 오류 발생", e);
        }
    }

    /**
     * 예약 확정 - Phase 2 (결제 완료 후 호출)
     */
    public boolean confirmReservationWithWal(String reservationId, String customerId, String orderId, String paymentId) {

        log.info("Confirming reservation with WAL: reservationId={}, orderId={}, paymentId={}",
                reservationId, orderId, paymentId);

        String transactionId = IdGenerator.generateTransactionId();

        try {
            // ========================================
            // Phase 2: WAL 기록 + DB 확정 처리
            // ========================================

            // 2-1. WAL Phase 2 시작 로그
            WalLogEntry phase2StartLog = createConfirmationStartLog(transactionId, reservationId, orderId, paymentId);
            walLogRepository.writeLog(phase2StartLog);

            // 2-2. Redis에서 예약 확정
            boolean redisConfirmed = redisReservationService.confirmReservation(reservationId);

            if (!redisConfirmed) {
                walLogRepository.updateLogStatus(phase2StartLog.getLogId(), "FAILED", "Redis 예약 확정 실패");
                return false;
            }

            // 2-3. 캐시 업데이트 (확정 상태로 변경)
            String cacheKey = "reservation:" + reservationId;
            InventoryReservation reservation = (InventoryReservation) cacheService.getCachedData(cacheKey);
            if (reservation != null) {
                reservation.setStatus(ReservationStatus.CONFIRMED);
                cacheService.cacheData(cacheKey, reservation, 86400); // 24시간으로 연장
            }

            // 2-4. WAL Phase 2 완료 로그
            WalLogEntry phase2CompleteLog = createConfirmationCompleteLog(transactionId, reservationId, orderId, paymentId);
            walLogRepository.writeLog(phase2CompleteLog);
            walLogRepository.updateLogStatus(phase2StartLog.getLogId(), "COMMITTED", "Phase 2 완료: 예약 확정 성공");

            log.info("Reservation confirmed successfully with WAL: reservationId={}, transactionId={}",
                    reservationId, transactionId);

            return true;

        } catch (Exception e) {
            log.error("Error confirming reservation with WAL: reservationId={}, transactionId={}",
                    reservationId, transactionId, e);

            // WAL 에러 로그 기록
            try {
                walLogRepository.writeLog(createConfirmationErrorLog(transactionId, reservationId, e.getMessage()));
            } catch (Exception walError) {
                log.error("Failed to write WAL error log", walError);
            }

            return false;
        }
    }

    /**
     * 예약 취소
     */
    public boolean cancelReservation(String reservationId, String customerId) {
        try {
            log.info("Cancelling reservation: reservationId={}, customerId={}", reservationId, customerId);

            // Redis에서 예약 취소
            boolean cancelled = redisReservationService.cancelReservation(reservationId);

            if (cancelled) {
                // 캐시 정리
                String cacheKey = "reservation:" + reservationId;
                cacheService.deleteCache(cacheKey);

                log.info("Reservation cancelled: reservationId={}", reservationId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Error cancelling reservation: reservationId={}", reservationId, e);
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
                return (InventoryReservation) cachedData;
            }

            return null;

        } catch (Exception e) {
            log.error("Error getting reservation: reservationId={}", reservationId, e);
            return null;
        }
    }

    // ========================================
    // WAL 로그 생성 헬퍼 메서드들
    // ========================================

    private WalLogEntry createReservationStartLog(String transactionId, String reservationId,
                                                  String productId, String customerId, Integer quantity) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("INVENTORY_RESERVE_START")
                .tableName("reservations")
                .beforeData(null)
                .afterData(String.format(
                        "{\"reservationId\":\"%s\",\"productId\":\"%s\",\"customerId\":\"%s\",\"quantity\":%d,\"status\":\"RESERVED\"}",
                        reservationId, productId, customerId, quantity))
                .status("PENDING")
                .message("재고 예약 시작")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createReservationCompleteLog(String transactionId, String reservationId,
                                                     InventoryReservation reservation) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("INVENTORY_RESERVE_COMPLETE")
                .tableName("reservations")
                .beforeData(null)
                .afterData(String.format(
                        "{\"reservationId\":\"%s\",\"status\":\"RESERVED\",\"expiresAt\":\"%s\"}",
                        reservationId, reservation.getExpiresAt()))
                .status("COMMITTED")
                .message("재고 예약 완료")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createReservationErrorLog(String transactionId, String reservationId, String errorMessage) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("INVENTORY_RESERVE_ERROR")
                .tableName("reservations")
                .beforeData(null)
                .afterData(String.format("{\"reservationId\":\"%s\",\"error\":\"%s\"}", reservationId, errorMessage))
                .status("FAILED")
                .message("재고 예약 실패: " + errorMessage)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createConfirmationStartLog(String transactionId, String reservationId,
                                                   String orderId, String paymentId) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("INVENTORY_CONFIRM_START")
                .tableName("reservations")
                .beforeData(String.format("{\"reservationId\":\"%s\",\"status\":\"RESERVED\"}", reservationId))
                .afterData(String.format(
                        "{\"reservationId\":\"%s\",\"orderId\":\"%s\",\"paymentId\":\"%s\",\"status\":\"CONFIRMED\"}",
                        reservationId, orderId, paymentId))
                .status("PENDING")
                .message("예약 확정 시작")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createConfirmationCompleteLog(String transactionId, String reservationId,
                                                      String orderId, String paymentId) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("INVENTORY_CONFIRM_COMPLETE")
                .tableName("reservations")
                .beforeData(String.format("{\"reservationId\":\"%s\",\"status\":\"RESERVED\"}", reservationId))
                .afterData(String.format(
                        "{\"reservationId\":\"%s\",\"orderId\":\"%s\",\"paymentId\":\"%s\",\"status\":\"CONFIRMED\"}",
                        reservationId, orderId, paymentId))
                .status("COMMITTED")
                .message("예약 확정 완료")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalLogEntry createConfirmationErrorLog(String transactionId, String reservationId, String errorMessage) {
        return WalLogEntry.builder()
                .logId(IdGenerator.generateEventId())
                .transactionId(transactionId)
                .operation("INVENTORY_CONFIRM_ERROR")
                .tableName("reservations")
                .beforeData(null)
                .afterData(String.format("{\"reservationId\":\"%s\",\"error\":\"%s\"}", reservationId, errorMessage))
                .status("FAILED")
                .message("예약 확정 실패: " + errorMessage)
                .createdAt(LocalDateTime.now())
                .build();
    }
}