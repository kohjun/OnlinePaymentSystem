/**
 * ========================================
 * 1. ReservationService (재고 선점 전담)
 * ========================================
 */
package com.example.payment.application.service;

import com.example.payment.domain.model.ReservationState;
import com.example.payment.domain.model.inventory.Reservation;
import com.example.payment.domain.repository.ReservationRepository;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import com.example.payment.infrastructure.buffer.WriteBufferService;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.infrastructure.persistance.redis.repository.CacheService;
import com.example.payment.presentation.dto.response.ReservationStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationService {

    private final ResourceReservationService redisReservationService;
    private final ReservationRepository reservationRepository;
    private final WriteBufferService writeBufferService;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    private static final int RESERVATION_TTL_SECONDS = 300;

    /**
     * 재고 즉시 선점 (Redis 기반 고속 처리)
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

            // 3. MySQL 쓰기를 버퍼에 비동기 큐잉 (논블로킹) ← 핵심 변경점
            LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(RESERVATION_TTL_SECONDS);

            ReservationWriteCommand writeCommand = new ReservationWriteCommand(
                    reservationId, productId, customerId, quantity, "PENDING", expiresAt
            );

            writeBufferService.enqueue(writeCommand); // 비동기 처리

            // 4. 도메인 모델 생성 및 캐시 저장 (기존과 동일)
            ReservationState reservationState = ReservationState.builder()
                    .reservationId(reservationId)
                    .productId(productId)
                    .customerId(customerId)
                    .quantity(quantity)
                    .status("RESERVED")
                    .createdAt(LocalDateTime.now())
                    .expiresAt(expiresAt)
                    .build();

            String cacheKey = "reservation-state:" + reservationId;
            cacheService.cacheData(cacheKey, reservationState, RESERVATION_TTL_SECONDS);

            log.info("Inventory reservation successful: reservationId={}, expiresAt={}",
                    reservationId, expiresAt);

            // 5. 즉시 성공 응답 반환 (MySQL 쓰기 완료를 기다리지 않음)
            return ReservationStatusResponse.builder()
                    .reservationId(reservationId)
                    .productId(productId)
                    .quantity(quantity)
                    .status("RESERVED")
                    .expiresAt(expiresAt)
                    .remainingSeconds(reservationState.getRemainingSeconds())
                    .message("재고가 선점되었습니다. " + (RESERVATION_TTL_SECONDS / 60) + "분 내에 결제를 완료해주세요.")
                    .build();

        } catch (Exception e) {
            log.error("Error during inventory reservation: productId={}, customerId={}",
                    productId, customerId, e);

            return ReservationStatusResponse.failed(productId, quantity, "SYSTEM_ERROR",
                    "시스템 오류가 발생했습니다: " + e.getMessage());
        }
    }

    /**
     * 예약 상태 조회
     */
    public ReservationStatusResponse getReservationStatus(String reservationId) {
        log.debug("Getting reservation status: reservationId={}", reservationId);

        try {
            // 1. 캐시에서 예약 상태 조회
            String cacheKey = "reservation-state:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData != null) {
                ReservationState state;
                if (cachedData instanceof ReservationState) {
                    state = (ReservationState) cachedData;
                } else {
                    state = objectMapper.convertValue(cachedData, ReservationState.class);
                }

                // 만료 확인
                if (state.isExpired()) {
                    return ReservationStatusResponse.builder()
                            .reservationId(reservationId)
                            .productId(state.getProductId())
                            .quantity(state.getQuantity())
                            .status("EXPIRED")
                            .expiresAt(state.getExpiresAt())
                            .remainingSeconds(0L)
                            .message("예약이 만료되었습니다.")
                            .build();
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

            // 2. 캐시에 없으면 DB에서 조회
            Optional<Reservation> dbReservation = reservationRepository.findById(reservationId);
            if (dbReservation.isPresent()) {
                Reservation reservation = dbReservation.get();

                boolean isExpired = LocalDateTime.now().isAfter(reservation.getExpiresAt());
                String status = isExpired ? "EXPIRED" : reservation.getStatus().name();

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
     */
    @Transactional
    public boolean cancelReservation(String reservationId, String customerId) {
        log.info("Cancelling reservation: reservationId={}, customerId={}", reservationId, customerId);

        try {
            // 1. 예약 상태 확인
            String cacheKey = "reservation-state:" + reservationId;
            Object cachedData = cacheService.getCachedData(cacheKey);

            if (cachedData == null) {
                log.warn("Reservation not found in cache: reservationId={}", reservationId);
                return false;
            }

            ReservationState state;
            if (cachedData instanceof ReservationState) {
                state = (ReservationState) cachedData;
            } else {
                state = objectMapper.convertValue(cachedData, ReservationState.class);
            }

            // 2. 소유자 확인
            if (!customerId.equals(state.getCustomerId())) {
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

            // 4. Redis에서 예약 취소 처리
            boolean redisCancelled = redisReservationService.cancelReservation(reservationId);
            if (!redisCancelled) {
                log.error("Failed to cancel reservation in Redis: reservationId={}", reservationId);
                return false;
            }

            // 5. DB 상태 업데이트
            Optional<Reservation> dbReservationOpt = reservationRepository.findById(reservationId);
            if (dbReservationOpt.isPresent()) {
                Reservation dbReservation = dbReservationOpt.get();
                dbReservation.setStatus(Reservation.ReservationStatus.CANCELLED);
                reservationRepository.save(dbReservation);
            }

            // 6. 캐시 정리
            cacheService.deleteCache(cacheKey);

            log.info("Reservation cancelled successfully: reservationId={}", reservationId);
            return true;

        } catch (Exception e) {
            log.error("Error cancelling reservation: reservationId={}, customerId={}",
                    reservationId, customerId, e);
            return false;
        }
    }
}