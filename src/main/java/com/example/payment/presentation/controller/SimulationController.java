package com.example.payment.presentation.controller;

import com.example.payment.application.service.SimulationService;
import com.example.payment.domain.model.EventConfig;
import com.example.payment.domain.model.inventory.Product;
import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.entity.OrderRecord;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.OrderRecordRepository;
import com.example.payment.presentation.dto.request.ProductDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
@Slf4j
public class SimulationController {

    private final SimulationService simulationService;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRecordRepository orderRecordRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/events")
    public ResponseEntity<List<EventConfig>> getEvents() {
        return ResponseEntity.ok(simulationService.getAllEvents());
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> createEvent(@RequestBody EventConfig config) {
        simulationService.createEvent(config);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "이벤트가 등록되었습니다."));
    }

    @GetMapping("/events/active")
    public ResponseEntity<EventConfig> getActiveEvent() {
        String activeId = simulationService.getActiveEventId();
        return ResponseEntity.ok(simulationService.getEvent(activeId));
    }

    @PostMapping("/events/active")
    public ResponseEntity<Map<String, Object>> setActiveEvent(@RequestParam String eventId) {
        simulationService.setActiveEventId(eventId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "활성 상품/이벤트가 전환되었습니다."));
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runSimulation(
            @RequestParam(defaultValue = "50") int vus,
            @RequestParam(defaultValue = "15") int duration) {
        return ResponseEntity.ok(simulationService.startSimulation(vus, duration));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopSimulation() {
        simulationService.stopSimulation();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "시뮬레이션 부하 테스트가 중단되었습니다."));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSimulationStatus() {
        return ResponseEntity.ok(simulationService.getSimulationStatus());
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetEvent() {
        String activeId = simulationService.getActiveEventId();
        simulationService.resetEventInventory(activeId);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "상품/이벤트 상태가 초기화되었습니다."));
    }

    @PostMapping("/bid")
    public ResponseEntity<Map<String, Object>> placeBid(
            @RequestParam String eventId,
            @RequestParam String customerId,
            @RequestParam double bidAmount) {
        Map<String, Object> result = simulationService.processAuctionBid(eventId, customerId, bidAmount);
        if ("SUCCESS".equals(result.get("status"))) {
            return ResponseEntity.ok(result);
        } else if ("REJECTED".equals(result.get("status"))) {
            return ResponseEntity.status(422).body(result);
        } else {
            return ResponseEntity.status(409).body(result);
        }
    }

    @GetMapping("/auction/status")
    public ResponseEntity<Map<String, Object>> getAuctionStatus(@RequestParam String eventId) {
        return ResponseEntity.ok(simulationService.getAuctionStatus(eventId));
    }

    @PostMapping("/products/register")
    public ResponseEntity<Map<String, Object>> registerProduct(@RequestBody ProductDto dto) {
        try {
            if (dto.getName() == null || dto.getName().isEmpty() ||
                dto.getCategory() == null || dto.getCategory().isEmpty() ||
                dto.getPrice() == null || dto.getQuantity() == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", "필수 항목을 누락했습니다."));
            }

            String productId = "PROD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            
            // Save Product
            Product product = Product.builder()
                    .id(productId)
                    .name(dto.getName())
                    .description(dto.getDescription())
                    .price(dto.getPrice())
                    .category(dto.getCategory())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            productRepository.save(product);

            // Save Inventory
            Inventory inventory = Inventory.builder()
                    .productId(productId)
                    .totalQuantity(dto.getQuantity())
                    .availableQuantity(dto.getQuantity())
                    .reservedQuantity(0)
                    .version(0L)
                    .lastUpdatedAt(LocalDateTime.now())
                    .build();
            inventoryRepository.save(inventory);

            // Initialize Redis keys for this product
            String resourceKey = "inventory:" + productId;
            redisTemplate.opsForHash().putAll(resourceKey, Map.of(
                    "total", dto.getQuantity(),
                    "available", dto.getQuantity()
            ));
            
            log.info("Successfully registered custom B2B product: id={}, name={}, qty={}", productId, dto.getName(), dto.getQuantity());
            
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "productId", productId,
                    "message", "상품이 성공적으로 등록되었습니다."
            ));
        } catch (Exception e) {
            log.error("Failed to register dynamic product", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "FAILED", "message", "상품 등록 실패: " + e.getMessage()));
        }
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<Map<String, Object>> getProductDetails(@PathVariable String productId) {
        try {
            // First try PostgreSQL (dynamically registered products)
            Product product = productRepository.findById(productId).orElse(null);
            if (product != null) {
                Optional<Inventory> invOpt = inventoryRepository.findById(productId);
                int total = invOpt.isPresent() ? invOpt.get().getTotalQuantity() : 0;
                int available = invOpt.isPresent() ? invOpt.get().getAvailableQuantity() : 0;

                return ResponseEntity.ok(Map.of(
                        "productId", product.getId(),
                        "name", product.getName(),
                        "description", product.getDescription() != null ? product.getDescription() : "",
                        "price", product.getPrice(),
                        "category", product.getCategory(),
                        "totalQuantity", total,
                        "availableQuantity", available
                ));
            }

            // Fallback: check in-memory preset events (CONCERT-IU, DRAW-NIKE, AUCTION-ROLEX, etc.)
            EventConfig event = simulationService.getEvent(productId);
            if (event != null) {
                return ResponseEntity.ok(Map.of(
                        "productId", event.getEventId(),
                        "name", event.getTitle(),
                        "description", event.getTitle(),
                        "price", event.getPrice(),
                        "category", event.getType(),
                        "totalQuantity", event.getTotalInventory(),
                        "availableQuantity", event.getTotalInventory()
                ));
            }

            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to get product details for {}", productId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/products")
    public ResponseEntity<List<Map<String, Object>>> getAllProducts() {
        try {
            List<Product> products = productRepository.findAll();
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Product p : products) {
                Optional<Inventory> invOpt = inventoryRepository.findById(p.getId());
                int total = invOpt.isPresent() ? invOpt.get().getTotalQuantity() : 0;
                int available = invOpt.isPresent() ? invOpt.get().getAvailableQuantity() : 0;
                result.add(Map.of(
                        "productId", p.getId(),
                        "name", p.getName(),
                        "description", p.getDescription() != null ? p.getDescription() : "",
                        "price", p.getPrice(),
                        "category", p.getCategory(),
                        "totalQuantity", total,
                        "availableQuantity", available
                ));
            }
            result.sort((r1, r2) -> ((String)r2.get("productId")).compareTo((String)r1.get("productId")));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get all products", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/orders")
    public ResponseEntity<List<OrderRecord>> getOrders() {
        try {
            List<OrderRecord> orders = orderRecordRepository.findAll();
            // Sort by createdAt descending
            orders.sort((o1, o2) -> o2.getCreatedAt().compareTo(o1.getCreatedAt()));
            return ResponseEntity.ok(orders);
        } catch (Exception e) {
            log.error("Failed to fetch order list", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
