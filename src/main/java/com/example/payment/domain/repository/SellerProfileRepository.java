package com.example.payment.domain.repository;

import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.model.marketplace.SellerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SellerProfileRepository extends JpaRepository<SellerProfile, String> {
    List<SellerProfile> findByStatus(SellerStatus status);
}
