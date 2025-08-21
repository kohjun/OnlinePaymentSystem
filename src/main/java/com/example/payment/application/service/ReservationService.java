/**
 * ========================================
 * 1. ReservationService (재고 선점 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.model.ReservationState;
import com.example.payment.domain.model.inventory.Reservation;
import com.example.payment.domain.repository.ReservationRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.infrastructure.buffer.WriteBufferService;
import com.example.payment.infrastructure.buffer.ReservationWriteCommand;  // 누락된 import 추가
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.presentation.dto.response.ReservationStatusResponse;
import com.example.payment.application.event.publisher.ReservationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationService {

    private final ResourceReservationService redisReservationService;
    private final ReservationRepository reservationRepository;
    private final WriteBufferService writeBufferService;
    private final CacheService cacheService;
    private final ReservationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private static final int RESERVATION_TTL_SECONDS = 300; // 5분

    /**
     * 재고 즉시 선점 (Redis 기반 고속 처리)
     * Write-Through with Buffer 패턴 적용
     */
    public ReservationStatusResponse reserveInventory(String productId, String customerId,
                                                      Integer quantity, String clientId) {

        log.info("Starting inventory reservation: productId={}, customerId={}, quantity={}",
                productId, customerId, quantity);

        try {
            // 1. 예약 ID 생성
            String reservationId = IdGenerator.generateReservationId();

            // 2. Redis에서 원자적 재고 선점 (즉시 처리)
            List<Object> result = redisReservationService.reserveResource(
                    "inventory:" + productId,
                    reservationId,
                    quantity,
                    RESERVATION_TTL_SECONDS
            );

            boolean success = (Boolean) result.get(0);
            String message = (String) result.get(1);

            if (!success) {
                log.warn("Inventory reservation failed: productId={}, reason={}", productId, message);
                return ReservationStatusResponse.failed(productId, quantity, "INSUFFICIENT_INVENTORY",
                        "재고 선점 실패: " + message);
            }

            // 3. 도메인 모델 생성 (즉시 캐시 저장 - Write-Through)
            LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(RESERVATION_TTL_SECONDS);

            ReservationState reservationState = ReservationState.builder()
                    .reservationId(reservationId)
                    .productId(productId)
                    .customerId(customerId)
                    .quantity(quantity)
                    .status("RESERVED")
                    .createdAt(LocalDateTime.now())
                    .expiresAt(expiresAt)
                    .build();

            // 4. 즉시 캐시에 저장 (Write-Through 패턴)
            String cacheKey = "reservation-state:" + reservationId;
            cacheService.cacheData(cacheKey, reservationState, RESERVATION_TTL_SECONDS);

            log.debug("Reservation state cached immediately: reservationId={}", reservationId);

            // 5. MySQL 쓰기를 버퍼에 비동기 큐잉 (Buffer 패턴)
            ReservationWriteCommand writeCommand = new ReservationWriteCommand(
                    reservationId, productId, customerId, quantity, "PENDING", expiresAt
            );

            writeBufferService.enqueue(writeCommand);
            log.debug("Reservation write command enqueued: reservationId={}", reservationId);

            // 6. 이벤트 발행 (비동기 후처리)
            ReservationStatusResponse response = ReservationStatusResponse.builder()
                    .reservationId(reservationId)
                    .productId(productId)
                    .quantity(quantity)
                    .status("RESERVED")
                    .expiresAt(expiresAt)
                    .remainingSeconds(reservationState.getRemainingSeconds())
                    .message("재고가 선점되었습니다. " + (RESERVATION_TTL_SECONDS / 60) + "분 내에 결제를 완료해주세요.")
                    .build();

            // 예약 생성 이벤트 발행
            eventPublisher.publishReservationCreated(response);

            log.info("Inventory reservation successful: reservationId={}, expiresAt={}",
                    reservationId, expiresAt);

            // 7. 즉시 성공 응답 반환 (MySQL 쓰기 완료를 기다리지 않음)
            return response;

        } catch (Exception e) {
            log.error("Error during inventory reservation: productId={}, customerId={}",
                    productId, customerId, e);

            return ReservationStatusResponse.failed(productId, quantity, "SYSTEM_ERROR",
                    "시스템 오류가 발생했습니다: " + e.getMessage());
        }
    }

    /**
     * 예약 상태 조회 (캐시 우선, DB 백업)
     */
    public ReservationStatusResponse getReservationStatus(String reservationId) {
        log.debug("Getting reservation status: reservationId={}", reservationId);

        try {
            // 1. 캐시에서 예약 상태 조회 (빠른 응답)
            String cacheKey = "reservation-state:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                ReservationState state = convertToReservationState(cachedData);

                // 만료 확인 및 처리
                if (state.isExpired()) {
                    log.info("Reservation expired: reservationId={}", reservationId);
                    handleExpiredReservation(reservationId, state);

                    return ReservationStatusResponse.expired(reservationId, state.getProductId(), state.getQuantity());
                }

                return ReservationStatusResponse.builder()
                        .reservationId(reservationId)
                        .productId(state.getProductId())
                        .quantity(state.getQuantity())
                        .status(state.getStatus())
                        .expiresAt(state.getExpiresAt())
                        .remainingSeconds(state.getRemainingSeconds())
                        .build();
            }

            // 2. 캐시에 없으면 DB에서 조회 (백업)
            log.debug("Cache miss, checking database: reservationId={}", reservationId);
            Optional<Reservation> dbReservation = reservationRepository.findById(reservationId);

            if (dbReservation.isPresent()) {
                Reservation reservation = dbReservation.get();
                boolean isExpired = LocalDateTime.now().isAfter(reservation.getExpiresAt());
                String status = isExpired ? "EXPIRED" : reservation.getStatus().name();

                // DB 데이터를 캐시에 복원 (Cache Warming)
                if (!isExpired) {
                    ReservationState stateFromDb = ReservationState.builder()
                            .reservationId(reservationId)
                            .productId(reservation.getProductId())
                            .customerId(reservation.getOrderId()) // customerId 매핑 필요
                            .quantity(reservation.getQuantity())
                            .status(status)
                            .createdAt(reservation.getCreatedAt())
                            .expiresAt(reservation.getExpiresAt())
                            .build();

                    long remainingTtl = java.time.Duration.between(LocalDateTime.now(), reservation.getExpiresAt()).getSeconds();
                    if (remainingTtl > 0) {
                        cacheService.cacheData(cacheKey, stateFromDb, (int) remainingTtl);
                        log.debug("Cache restored from DB: reservationId={}", reservationId);
                    }
                }

                return ReservationStatusResponse.builder()
                        .reservationId(reservationId)
                        .productId(reservation.getProductId())
                        .quantity(reservation.getQuantity())
                        .status(status)
                        .expiresAt(reservation.getExpiresAt())
                        .remainingSeconds(isExpired ? 0L :
                                java.time.Duration.between(LocalDateTime.now(), reservation.getExpiresAt()).getSeconds())
                        .build();
            }

            // 3. 예약을 찾을 수 없음
            log.warn("Reservation not found: reservationId={}", reservationId);
            return null;

        } catch (Exception e) {
            log.error("Error getting reservation status: reservationId={}", reservationId, e);
            return ReservationStatusResponse.builder()
                    .reservationId(reservationId)
                    .status("ERROR")
                    .remainingSeconds(0L)
                    .message("조회 중 오류가 발생했습니다.")
                    .build();
        }
    }

    /**
     * 예약 취소 (사용자 요청)
     * Write-Through 패턴: 캐시와 Redis 즉시 업데이트, DB는 비동기
     */
    @Transactional
    public boolean cancelReservation(String reservationId, String customerId) {
        log.info("Cancelling reservation: reservationId={}, customerId={}", reservationId, customerId);

        try {
            // 1. 캐시에서 예약 상태 확인
            String cacheKey = "reservation-state:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData == null) {
                log.warn("Reservation not found in cache: reservationId={}", reservationId);
                return false;
            }

            ReservationState state = convertToReservationState(cachedData);

            // 2. 소유자 확인
            if (!customerId.equals(state.getCustomerId()) && !"SYSTEM".equals(customerId)) {
                log.warn("Unauthorized cancellation attempt: reservationId={}, customerId={}",
                        reservationId, customerId);
                return false;
            }

            // 3. 상태 확인 (RESERVED 상태만 취소 가능)
            if (!"RESERVED".equals(state.getStatus())) {
                log.warn("Cannot cancel reservation in status: reservationId={}, status={}",
                        reservationId, state.getStatus());
                return false;
            }

            // 4. Redis에서 예약 취소 처리 (재고 복원)
            boolean redisCancelled = redisReservationService.cancelReservation(reservationId);
            if (!redisCancelled) {
                log.error("Failed to cancel reservation in Redis: reservationId={}", reservationId);
                return false;
            }

            // 5. 캐시 즉시 정리 (Write-Through)
            cacheService.deleteCache(cacheKey);
            log.debug("Reservation cache cleared: reservationId={}", reservationId);

            // 6. DB 상태 업데이트 (비동기 처리 또는 즉시 처리)
            try {
                Optional<Reservation> dbReservationOpt = reservationRepository.findById(reservationId);
                if (dbReservationOpt.isPresent()) {
                    Reservation dbReservation = dbReservationOpt.get();
                    dbReservation.setStatus(Reservation.ReservationStatus.CANCELLED);
                    reservationRepository.save(dbReservation);
                    log.debug("Reservation status updated in DB: reservationId={}", reservationId);
                }
            } catch (Exception dbError) {
                log.error("Error updating reservation in DB, but Redis/Cache updated: reservationId={}",
                        reservationId, dbError);
                // Redis와 캐시는 이미 업데이트되었으므로 계속 진행
            }

            // 7. 취소 이벤트 발행
            eventPublisher.publishReservationCancelled(reservationId, "사용자 요청");

            log.info("Reservation cancelled successfully: reservationId={}", reservationId);
            return true;

        } catch (Exception e) {
            log.error("Error cancelling reservation: reservationId={}, customerId={}",
                    reservationId, customerId, e);
            return false;
        }
    }

    /**
     * 만료된 예약 처리 (내부 메서드)
     */
    private void handleExpiredReservation(String reservationId, ReservationState state) {
        try {
            // Redis에서 예약 취소 처리 (재고 복원)
            redisReservationService.cancelReservation(reservationId);

            // 캐시에서 제거
            String cacheKey = "reservation-state:" + reservationId;
            cacheService.deleteCache(cacheKey);

            // 만료 이벤트 발행
            eventPublisher.publishReservationExpired(reservationId);

            log.info("Expired reservation processed: reservationId={}", reservationId);

        } catch (Exception e) {
            log.error("Error handling expired reservation: reservationId={}", reservationId, e);
        }
    }

    /**
     * Object를 ReservationState로 변환
     */
    private ReservationState convertToReservationState(Object cachedData) {
        if (cachedData instanceof ReservationState) {
            return (ReservationState) cachedData;
        } else {
            return objectMapper.convertValue(cachedData, ReservationState.class);
        }
    }

    /**
     * 고객의 활성 예약 목록 조회 (추가 기능)
     */
    public List<ReservationStatusResponse> getActiveReservationsByCustomer(String customerId) {
        log.debug("Getting active reservations for customer: {}", customerId);

        try {
            // 실제 구현에서는 고객별 인덱스나 별도 캐시 구조 필요
            // 여기서는 기본 구현만 제공
            String customerReservationsKey = "customer-reservations:" + customerId;
            Object cachedReservations = cacheService.getCachedData(customerReservationsKey);

            if (cachedReservations != null) {
                // 캐시된 예약 목록 처리
                log.debug("Found cached reservations for customer: {}", customerId);
            }

            // DB에서 조회하는 로직도 필요
            // List<Reservation> activeReservations = reservationRepository.findByCustomerIdAndStatus(customerId, PENDING);

            return java.util.Collections.emptyList(); // 임시 반환

        } catch (Exception e) {
            log.error("Error getting active reservations for customer: {}", customerId, e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 예약 통계 조회 (관리자용)
     */
    public java.util.Map<String, Object> getReservationStats(String productId) {
        try {
            // Redis와 DB에서 통계 정보 수집
            java.util.Map<String, Object> stats = new java.util.HashMap<>();

            // 현재 예약 중인 수량 (Redis)
            String inventoryKey = "inventory:" + productId;
            Object inventoryData = cacheService.getCachedData(inventoryKey);

            if (inventoryData != null) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> inventory = (java.util.Map<String, Object>) inventoryData;
                stats.put("current_reserved", inventory.get("reserved_quantity"));
                stats.put("available", inventory.get("available_quantity"));
            }

            // DB 통계 (실제 구현 필요)
            stats.put("total_reservations_today", 0);
            stats.put("cancelled_reservations_today", 0);
            stats.put("expired_reservations_today", 0);

            return stats;

        } catch (Exception e) {
            log.error("Error getting reservation stats for product: {}", productId, e);
            return java.util.Map.of("error", "통계 조회 실패: " + e.getMessage());
        }
    }
}