package com.example.payment.application.service;

import com.example.payment.domain.exception.ReservationException;
import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.model.reservation.InventoryReservation;
import com.example.payment.domain.model.reservation.ReservationStatus;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.infrastructure.persistence.redis.repository.CacheService;
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
 * 개선된 재고 예약 서비스
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationService {

    private final CacheService cacheService;
    private final ResourceReservationService redisReservationService;
    private final InventoryReservationRecordRepository reservationRecordRepository;

    private static final int DEFAULT_RESERVATION_TTL_SECONDS = 300; // 5분

    @Data
    @AllArgsConstructor
    public static class ReservationResult {
        private InventoryReservation reservation;
    }

    public ReservationResult reserveInventory(
            String transactionId,
            String productId,
            String customerId,
            Integer quantity,
            String clientId) {

        log.info("[Phase 1] Starting inventory reservation: txId={}, productId={}, customerId={}, quantity={}",
                transactionId, productId, customerId, quantity);

        String reservationId = IdGenerator.generateReservationId();
        String lockKey = "inventory:" + productId;

        try {
            // ===================================
            // 1. Redis에서 재고 선점 (Lua 스크립트)
            // ===================================
            boolean success = redisReservationService.reserveResource(
                    lockKey,
                    quantity,
                    Duration.ofSeconds(DEFAULT_RESERVATION_TTL_SECONDS),
                    reservationId
            );

            if (!success) {
                String message = "Redis 재고 선점 실패";
                log.warn("Inventory reservation failed: txId={}, productId={}, reason={}",
                        transactionId, productId, message);
                return null;
            }

            // ===================================
            // 2. 도메인 객체 생성
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

            reservationRecordRepository.save(InventoryReservationRecord.builder()
                    .reservationId(reservation.getReservationId())
                    .productId(reservation.getProductId())
                    .customerId(reservation.getCustomerId())
                    .quantity(reservation.getQuantity())
                    .status(reservation.getStatus().name())
                    .expiresAt(reservation.getExpiresAt())
                    .createdAt(reservation.getCreatedAt())
                    .build());

            // ===================================
            // 3. 캐시에 저장 (메타데이터 포함)
            // ===================================
            String cacheKey = "reservation:" + reservationId;
            cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

            String txMappingKey = "tx_reservation:" + transactionId;
            cacheService.cacheData(txMappingKey, reservationId, DEFAULT_RESERVATION_TTL_SECONDS);

            log.info("[Phase 1] Inventory reservation succeeded: txId={}, reservationId={}, productId={}",
                    transactionId, reservationId, productId);

            return new ReservationResult(reservation);

        } catch (Exception e) {
            log.error("[Phase 1] Error during inventory reservation: txId={}, productId={}, customerId={}",
                    transactionId, productId, customerId, e);
            throw new ReservationException("재고 선점 실패", e);
        }
    }

    /**
     * 개선: 예약 취소 - 트랜잭션 ID 주입
     */
    public boolean cancelReservation(String transactionId, String reservationId, String customerId) {
        try {
            log.info("🟠 Cancelling reservation: txId={}, reservationId={}, customerId={}",
                    transactionId, reservationId, customerId);

            InventoryReservation reservation = getReservation(reservationId);
            if (reservation == null) {
                log.warn("Reservation not found: reservationId={}", reservationId);
                return false;
            }

            if (!customerId.equals(reservation.getCustomerId()) && !"SYSTEM".equals(customerId)) {
                log.warn("Customer ID mismatch: reservationId={}, expected={}, actual={}",
                        reservationId, reservation.getCustomerId(), customerId);
                return false;
            }

            if (!reservation.canBeCancelled()) {
                log.warn("Reservation cannot be cancelled: reservationId={}, status={}",
                        reservationId, reservation.getStatus());
                return false;
            }

            String resourceKey = "inventory:" + reservation.getProductId();
            int quantityToRelease = reservation.getQuantity();

            boolean cancelled = redisReservationService.releaseResource(resourceKey, quantityToRelease, reservation.getReservationId());

            if (cancelled) {
                reservation.setStatus(ReservationStatus.CANCELLED);
                reservationRecordRepository.findById(reservationId).ifPresent(record -> {
                    record.setStatus(ReservationStatus.CANCELLED.name());
                    reservationRecordRepository.save(record);
                });

                String cacheKey = "reservation:" + reservationId;
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

                log.info("Reservation cancelled: txId={}, reservationId={}",
                        transactionId, reservationId);
                return true;

            } else {
                log.warn("Failed to cancel reservation in Redis: reservationId={}", reservationId);
                return false;
            }

        } catch (Exception e) {
            log.error("Error cancelling reservation: txId={}, reservationId={}",
                    transactionId, reservationId, e);
            return false;
        }
    }

    /**
     * 예약 조회
     */
    public InventoryReservation getReservation(String reservationId) {
        try {
            String cacheKey = "reservation:" + reservationId;
            InventoryReservation cachedData = cacheService.getCachedObject(cacheKey, InventoryReservation.class);

            if (cachedData != null) {
                log.debug("Reservation found in cache: reservationId={}", reservationId);
                return cachedData;
            }

            InventoryReservation reservation = reservationRecordRepository.findById(reservationId)
                    .map(this::toDomainReservation)
                    .orElse(null);
            if (reservation != null) {
                cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);
                log.debug("Reservation found in Postgres: reservationId={}", reservationId);
                return reservation;
            }

            log.debug("Reservation not found: reservationId={}", reservationId);
            return null;

        } catch (Exception e) {
            log.error("Error getting reservation: reservationId={}", reservationId, e);
            return null;
        }
    }

    /**
     * 예약 상태를 'CONFIRMED'로 업데이트하고 캐시를 갱신
     */
    public void confirmReservationStatus(InventoryReservation reservation) {
        try {
            if (reservation == null) {
                log.warn("Cannot confirm status for null reservation.");
                return;
            }

            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservationRecordRepository.findById(reservation.getReservationId()).ifPresent(record -> {
                record.setStatus(ReservationStatus.CONFIRMED.name());
                reservationRecordRepository.save(record);
            });

            String cacheKey = "reservation:" + reservation.getReservationId();
            cacheService.cacheData(cacheKey, reservation, DEFAULT_RESERVATION_TTL_SECONDS);

            log.info("Reservation status set to CONFIRMED and re-cached: {}", reservation.getReservationId());

        } catch (Exception e) {
            log.error("Error updating reservation status to CONFIRMED: {}", reservation.getReservationId(), e);
        }
    }

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

    private InventoryReservation toDomainReservation(InventoryReservationRecord record) {
        return InventoryReservation.builder()
                .reservationId(record.getReservationId())
                .productId(record.getProductId())
                .customerId(record.getCustomerId())
                .quantity(record.getQuantity())
                .status(ReservationStatus.valueOf(record.getStatus()))
                .createdAt(record.getCreatedAt())
                .expiresAt(record.getExpiresAt())
                .build();
    }
}
