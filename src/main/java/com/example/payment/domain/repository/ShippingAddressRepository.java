package com.example.payment.domain.repository;

import com.example.payment.domain.model.account.ShippingAddress;
import com.example.payment.domain.model.account.ShippingAddressStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShippingAddressRepository extends JpaRepository<ShippingAddress, String> {
    List<ShippingAddress> findByUserIdAndStatusOrderByDefaultAddressDescCreatedAtDesc(
            String userId,
            ShippingAddressStatus status
    );

    Optional<ShippingAddress> findByAddressIdAndCustomerIdAndStatus(
            String addressId,
            String customerId,
            ShippingAddressStatus status
    );
}
