package com.example.payment.repository;

import com.example.payment.domain.Inventory;
import com.example.payment.domain.InventoryTransaction;
import com.example.payment.domain.Product;
import com.example.payment.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
/**
 * 예약 리포지토리
 */
@Repository
public interface ReservationRepository extends JpaRepository<Reservation, String> {

    List<Reservation> findByProductIdAndStatus(String productId, Reservation.ReservationStatus status);

    List<Reservation> findByOrderId(String orderId);

    List<Reservation> findByPaymentId(String paymentId);

    /**
     * 만료된 예약 조회
     */
    @Query("SELECT r FROM Reservation r WHERE r.expiresAt < :now AND r.status = 'PENDING'")
    List<Reservation> findExpiredReservations(@Param("now") LocalDateTime now);

    /**
     * 특정 기간 동안의 예약 조회
     */
    @Query("SELECT r FROM Reservation r WHERE r.createdAt BETWEEN :startDate AND :endDate")
    List<Reservation> findByDateRange(@Param("startDate") LocalDateTime startDate,
                                      @Param("endDate") LocalDateTime endDate);

    /**
     * 상품별 예약 통계
     */
    @Query("SELECT r.productId, r.status, COUNT(r), SUM(r.quantity) FROM Reservation r " +
            "WHERE r.productId = :productId GROUP BY r.productId, r.status")
    List<Object[]> getReservationStatsByProduct(@Param("productId") String productId);
}