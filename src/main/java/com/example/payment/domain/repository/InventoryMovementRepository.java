package com.example.payment.domain.repository;

import com.example.payment.domain.entity.InventoryMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, String> {
    Optional<InventoryMovement> findByOperationKey(String operationKey);
}
