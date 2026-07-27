package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SaleEventRepository extends JpaRepository<SaleEvent, String> {
    List<SaleEvent> findByStatusInOrderByStartsAtAsc(Collection<SaleEventStatus> statuses);

    List<SaleEvent> findByStatusAndSaleTypeOrderByStartsAtAsc(SaleEventStatus status, SaleType saleType);

    List<SaleEvent> findBySaleTypeAndStatusAndEndsAtLessThanEqual(
            SaleType saleType,
            SaleEventStatus status,
            LocalDateTime endsAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update SaleEvent event
               set event.status = :endedStatus,
                   event.updatedAt = :closedAt
             where event.status = :liveStatus
               and event.saleType in :saleTypes
               and event.endsAt is not null
               and event.endsAt <= :closedAt
            """)
    int endDueEvents(@Param("saleTypes") Collection<SaleType> saleTypes,
                     @Param("liveStatus") SaleEventStatus liveStatus,
                     @Param("endedStatus") SaleEventStatus endedStatus,
                     @Param("closedAt") LocalDateTime closedAt);

    List<SaleEvent> findByListingIdOrderByStartsAtDesc(String listingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from SaleEvent e where e.saleEventId = :eventId")
    Optional<SaleEvent> findByIdForUpdate(@Param("eventId") String eventId);

    @Query(value = """
            select distinct event
            from SaleEvent event
            join MarketplaceListing listing on listing.listingId = event.listingId
            left join Product product on product.id = event.productId
            left join SellerProfile seller on seller.sellerId = event.sellerId
            where event.status in :statuses
              and listing.status = com.example.payment.domain.model.marketplace.ListingStatus.ACTIVE
              and (:saleType is null or event.saleType = :saleType)
              and (:keyword is null
                   or lower(listing.title) like :keyword
                   or lower(listing.description) like :keyword
                   or lower(product.name) like :keyword
                   or lower(product.description) like :keyword
                   or lower(seller.displayName) like :keyword
                   or lower(event.productId) like :keyword)
            """,
            countQuery = """
            select count(distinct event)
            from SaleEvent event
            join MarketplaceListing listing on listing.listingId = event.listingId
            left join Product product on product.id = event.productId
            left join SellerProfile seller on seller.sellerId = event.sellerId
            where event.status in :statuses
              and listing.status = com.example.payment.domain.model.marketplace.ListingStatus.ACTIVE
              and (:saleType is null or event.saleType = :saleType)
              and (:keyword is null
                   or lower(listing.title) like :keyword
                   or lower(listing.description) like :keyword
                   or lower(product.name) like :keyword
                   or lower(product.description) like :keyword
                   or lower(seller.displayName) like :keyword
                   or lower(event.productId) like :keyword)
            """)
    Page<SaleEvent> searchPublicEvents(@Param("statuses") Collection<SaleEventStatus> statuses,
                                       @Param("saleType") SaleType saleType,
                                       @Param("keyword") String keyword,
                                       Pageable pageable);

    /**
     * 검색 색인이 고른 후보 중에서 공개 조건을 만족하는 것만 돌려준다.
     *
     * 공개 여부·판매 방식 조건은 키워드 질의와 똑같이 적용한다. 색인이
     * 뒤처져 이미 내려간 상품을 후보로 주더라도 여기서 걸러지므로,
     * 검색 결과에 판매가 끝난 상품이 섞이지 않는다.
     */
    @Query(value = """
            select distinct event
            from SaleEvent event
            join MarketplaceListing listing on listing.listingId = event.listingId
            where event.status in :statuses
              and listing.status = com.example.payment.domain.model.marketplace.ListingStatus.ACTIVE
              and (:saleType is null or event.saleType = :saleType)
              and event.saleEventId in :saleEventIds
            """,
            countQuery = """
            select count(distinct event)
            from SaleEvent event
            join MarketplaceListing listing on listing.listingId = event.listingId
            where event.status in :statuses
              and listing.status = com.example.payment.domain.model.marketplace.ListingStatus.ACTIVE
              and (:saleType is null or event.saleType = :saleType)
              and event.saleEventId in :saleEventIds
            """)
    Page<SaleEvent> findPublicEventsByIds(@Param("statuses") Collection<SaleEventStatus> statuses,
                                          @Param("saleType") SaleType saleType,
                                          @Param("saleEventIds") Collection<String> saleEventIds,
                                          Pageable pageable);
}
