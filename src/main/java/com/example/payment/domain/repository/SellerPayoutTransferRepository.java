package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.SellerPayoutTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SellerPayoutTransferRepository extends JpaRepository<SellerPayoutTransfer, String> {
    Optional<SellerPayoutTransfer> findByPayoutIdAndIdempotencyKey(String payoutId, String idempotencyKey);

    List<SellerPayoutTransfer> findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<com.example.payment.domain.model.marketplace.PayoutTransferStatus> statuses,
            LocalDateTime updatedBefore,
            Pageable pageable);
}
