package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.model.marketplace.SellerStatus;
import com.example.payment.domain.model.marketplace.SellerVerificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SellerProfileRepository extends JpaRepository<SellerProfile, String> {
    List<SellerProfile> findByStatus(SellerStatus status);

    Optional<SellerProfile> findByOwnerUserId(String ownerUserId);

    Optional<SellerProfile> findByOwnerCustomerId(String ownerCustomerId);

    long countByVerificationStatus(SellerVerificationStatus verificationStatus);

    List<SellerProfile> findByVerificationStatusOrderByVerificationSubmittedAtAsc(
            SellerVerificationStatus verificationStatus,
            Pageable pageable
    );
}
