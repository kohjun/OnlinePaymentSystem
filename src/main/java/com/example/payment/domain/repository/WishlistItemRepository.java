package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.WishlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WishlistItemRepository extends JpaRepository<WishlistItem, String> {

    List<WishlistItem> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    Optional<WishlistItem> findByCustomerIdAndSaleEventId(String customerId, String saleEventId);

    boolean existsByCustomerIdAndSaleEventId(String customerId, String saleEventId);

    long deleteByCustomerIdAndSaleEventId(String customerId, String saleEventId);

    long countBySaleEventId(String saleEventId);

    /** 목록 화면에서 어떤 카드가 이미 찜 상태인지 한 번에 판별한다. */
    List<WishlistItem> findByCustomerIdAndSaleEventIdIn(String customerId, Collection<String> saleEventIds);
}
