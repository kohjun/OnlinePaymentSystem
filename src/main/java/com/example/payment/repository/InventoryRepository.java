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
 * 재고 리포지토리
 */
@Repository
public interface InventoryRepository extends JpaRepository<Inventory, String> {

    /**
     * 낙관적 락을 사용한 재고 업데이트
     */
    @Modifying
    @Query("UPDATE Inventory i SET i.availableQuantity = i.availableQuantity - :quantity, " +
            "i.reservedQuantity = i.reservedQuantity + :quantity, i.version = i.version + 1 " +
            "WHERE i.productId = :productId AND i.availableQuantity >= :quantity AND i.version = :version")
    int reserveInventoryWithVersion(@Param("productId") String productId,
                                    @Param("quantity") int quantity,
                                    @Param("version") Long version);

    /**
     * 예약 확정 (재고 차감)
     */
    @Modifying
    @Query("UPDATE Inventory i SET i.reservedQuantity = i.reservedQuantity - :quantity, " +
            "i.totalQuantity = i.totalQuantity - :quantity, i.version = i.version + 1 " +
            "WHERE i.productId = :productId AND i.reservedQuantity >= :quantity AND i.version = :version")
    int confirmReservationWithVersion(@Param("productId") String productId,
                                      @Param("quantity") int quantity,
                                      @Param("version") Long version);

    /**
     * 예약 취소 (재고 복원)
     */
    @Modifying
    @Query("UPDATE Inventory i SET i.reservedQuantity = i.reservedQuantity - :quantity, " +
            "i.availableQuantity = i.availableQuantity + :quantity, i.version = i.version + 1 " +
            "WHERE i.productId = :productId AND i.reservedQuantity >= :quantity AND i.version = :version")
    int cancelReservationWithVersion(@Param("productId") String productId,
                                     @Param("quantity") int quantity,
                                     @Param("version") Long version);

    /**
     * 가용 재고가 있는 상품 조회
     */
    @Query("SELECT i FROM Inventory i WHERE i.availableQuantity >= :minQuantity")
    List<Inventory> findByAvailableQuantityGreaterThanEqual(@Param("minQuantity") int minQuantity);

    /**
     * 재고 부족 상품 조회
     */
    @Query("SELECT i FROM Inventory i WHERE i.availableQuantity < :threshold")
    List<Inventory> findLowStockProducts(@Param("threshold") int threshold);
}