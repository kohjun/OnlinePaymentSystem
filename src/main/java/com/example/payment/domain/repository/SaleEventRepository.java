package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SaleEventRepository extends JpaRepository<SaleEvent, String> {
    List<SaleEvent> findByStatusInOrderByStartsAtAsc(Collection<SaleEventStatus> statuses);

    List<SaleEvent> findByStatusAndSaleTypeOrderByStartsAtAsc(SaleEventStatus status, SaleType saleType);

    List<SaleEvent> findByListingIdOrderByStartsAtDesc(String listingId);
}
