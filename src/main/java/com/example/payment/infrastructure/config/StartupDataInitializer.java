package com.example.payment.infrastructure.config;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.inventory.Product;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupDataInitializer implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final ResourceReservationService resourceReservationService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedProduct("PROD-001", "Test Smartphone", "Test smartphone product",
                new BigDecimal("799.99"), "ELECTRONICS", 100);
        seedProduct("PROD-002", "Test Earbuds", "Test wireless earbuds",
                new BigDecimal("129.99"), "ELECTRONICS", 50);
        seedProduct("SAGA-TEST-001", "Saga Test Product", "Temporal reservation test product",
                new BigDecimal("100.00"), "TEST", 200);

        List.of("PROD-001", "PROD-002", "SAGA-TEST-001").forEach(productId ->
                inventoryRepository.findById(productId).ifPresent(inventory ->
                        initializeRedisInventoryIfMissing(
                                productId,
                                inventory.getTotalQuantity(),
                                inventory.getAvailableQuantity()
                        )));
    }

    private void initializeRedisInventoryIfMissing(String productId, int total, int available) {
        String resourceKey = "inventory:" + productId;
        Map<String, Object> current = resourceReservationService.getResourceStatus(resourceKey);
        if (current.isEmpty()) {
            resourceReservationService.initializeResource(resourceKey, total, available);
            return;
        }
        log.info("Redis inventory already exists, skipping initialization: key={}", resourceKey);
    }

    private void seedProduct(String id, String name, String description,
                             BigDecimal price, String category, int quantity) {
        productRepository.findById(id).orElseGet(() -> productRepository.save(Product.builder()
                .id(id)
                .name(name)
                .description(description)
                .price(price)
                .category(category)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build()));

        inventoryRepository.findById(id).orElseGet(() -> inventoryRepository.save(Inventory.builder()
                .productId(id)
                .totalQuantity(quantity)
                .availableQuantity(quantity)
                .reservedQuantity(0)
                .version(0L)
                .lastUpdatedAt(LocalDateTime.now())
                .build()));

        log.info("Seed data ready: productId={}, quantity={}", id, quantity);
    }
}
