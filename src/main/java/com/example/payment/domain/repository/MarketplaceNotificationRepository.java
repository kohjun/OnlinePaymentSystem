package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.MarketplaceNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MarketplaceNotificationRepository extends JpaRepository<MarketplaceNotification, String> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    @Query("""
            select notification from MarketplaceNotification notification
            where (:userId is not null and notification.recipientUserId = :userId)
               or (:customerId is not null and notification.recipientCustomerId = :customerId)
               or (:sellerId is not null and notification.recipientSellerId = :sellerId)
            order by notification.createdAt desc
            """)
    List<MarketplaceNotification> findForRecipient(
            @Param("userId") String userId,
            @Param("customerId") String customerId,
            @Param("sellerId") String sellerId,
            Pageable pageable
    );

    @Query("""
            select count(notification) from MarketplaceNotification notification
            where notification.readAt is null
              and ((:userId is not null and notification.recipientUserId = :userId)
                or (:customerId is not null and notification.recipientCustomerId = :customerId)
                or (:sellerId is not null and notification.recipientSellerId = :sellerId))
            """)
    long countUnreadForRecipient(
            @Param("userId") String userId,
            @Param("customerId") String customerId,
            @Param("sellerId") String sellerId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MarketplaceNotification notification
               set notification.readAt = :readAt
             where notification.readAt is null
               and ((:userId is not null and notification.recipientUserId = :userId)
                 or (:customerId is not null and notification.recipientCustomerId = :customerId)
                 or (:sellerId is not null and notification.recipientSellerId = :sellerId))
            """)
    int markAllRead(
            @Param("userId") String userId,
            @Param("customerId") String customerId,
            @Param("sellerId") String sellerId,
            @Param("readAt") LocalDateTime readAt
    );
}
