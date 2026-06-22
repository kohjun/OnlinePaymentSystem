package com.example.payment.infrastructure.config;

import com.example.payment.domain.model.inventory.Inventory;
import com.example.payment.domain.model.inventory.Product;
import com.example.payment.domain.model.marketplace.ListingStatus;
import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.model.marketplace.SellerStatus;
import com.example.payment.domain.model.marketplace.SellerVerificationStatus;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
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
    private final SellerProfileRepository sellerProfileRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final SaleEventRepository saleEventRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedProduct("PROD-001", "Test Smartphone", "Test smartphone product",
                new BigDecimal("799.99"), "ELECTRONICS", 100);
        seedProduct("PROD-002", "Test Earbuds", "Test wireless earbuds",
                new BigDecimal("129.99"), "ELECTRONICS", 50);
        seedProduct("SAGA-TEST-001", "Saga Test Product", "Temporal reservation test product",
                new BigDecimal("100.00"), "TEST", 200);
        seedProduct("CONCURRENCY-TEST-001", "Concurrency Test Product", "High contention reservation test product",
                new BigDecimal("799.99"), "TEST", 3);
        seedProduct("DB-TEST-001", "Database Verification Product", "Database and Redis consistency test product",
                new BigDecimal("100.00"), "TEST", 10);
        seedProduct("PHASE2-TEST-001", "Phase 2 Failure Product", "Compensation scenario test product",
                new BigDecimal("100.00"), "TEST", 3);
        seedProduct("CONCERT-VIP", "PSY Summer Swag - VIP Seat", "Concert VIP Ticket",
                new BigDecimal("150000.00"), "CONCERT", 24);
        seedProduct("CONCERT-R", "PSY Summer Swag - R Seat", "Concert R Ticket",
                new BigDecimal("120000.00"), "CONCERT", 36);
        seedProduct("CONCERT-S", "PSY Summer Swag - S Seat", "Concert S Ticket",
                new BigDecimal("90000.00"), "CONCERT", 48);
        seedProduct("DRAW-NIKE", "나이키 에어 조던 1 레트로 한정판 드로우", "Air Jordan 1 Retro High",
                new BigDecimal("239000.00"), "DRAW", 10);
        seedProduct("AUCTION-ROLEX", "빈티지 롤렉스 서브마리너 경매", "Vintage Rolex Submariner",
                new BigDecimal("8500000.00"), "AUCTION", 1);

        seedMarketplaceCatalog();

        List.of("PROD-001", "PROD-002", "SAGA-TEST-001", "CONCURRENCY-TEST-001", "DB-TEST-001", "PHASE2-TEST-001", "CONCERT-VIP", "CONCERT-R", "CONCERT-S", "DRAW-NIKE", "AUCTION-ROLEX").forEach(productId ->
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

    private void seedMarketplaceCatalog() {
        seedSeller("SELLER-EVERYSALE-CURATED", "EverySale Curated", SellerVerificationStatus.VERIFIED);
        seedSeller("SELLER-LIVE-EVENTS", "Live Events Korea", SellerVerificationStatus.VERIFIED);

        seedListing(
                "LIST-DRAW-NIKE",
                "SELLER-EVERYSALE-CURATED",
                "DRAW-NIKE",
                "나이키 에어 조던 1 레트로 한정판 드로우",
                "한정 수량으로 진행되는 에브리세일 공식 드로우입니다.",
                "/jordan_sneakers.png",
                "NEW"
        );
        seedListing(
                "LIST-AUCTION-ROLEX",
                "SELLER-EVERYSALE-CURATED",
                "AUCTION-ROLEX",
                "빈티지 롤렉스 서브마리너 실시간 경매",
                "검수 예정 빈티지 컬렉터블 경매입니다.",
                "/rolex_watch.png",
                "PRE_OWNED"
        );
        seedListing(
                "LIST-CONCERT-VIP",
                "SELLER-LIVE-EVENTS",
                "CONCERT-VIP",
                "PSY Summer Swag VIP 드롭",
                "한정 좌석을 선착순 드롭 방식으로 판매합니다.",
                "/concert_poster.png",
                "DIGITAL_TICKET"
        );

        LocalDateTime now = LocalDateTime.now();
        seedSaleEvent(
                "EVT-DRAW-NIKE",
                "LIST-DRAW-NIKE",
                "SELLER-EVERYSALE-CURATED",
                "DRAW-NIKE",
                SaleType.RAFFLE,
                SaleEventStatus.LIVE,
                now.minusHours(1),
                now.plusDays(7),
                new BigDecimal("239000.00"),
                10,
                null,
                null
        );
        seedSaleEvent(
                "EVT-AUCTION-ROLEX",
                "LIST-AUCTION-ROLEX",
                "SELLER-EVERYSALE-CURATED",
                "AUCTION-ROLEX",
                SaleType.AUCTION,
                SaleEventStatus.LIVE,
                now.minusMinutes(30),
                now.plusHours(6),
                new BigDecimal("8500000.00"),
                1,
                new BigDecimal("100000.00"),
                new BigDecimal("8500000.00")
        );
        seedSaleEvent(
                "EVT-CONCERT-VIP",
                "LIST-CONCERT-VIP",
                "SELLER-LIVE-EVENTS",
                "CONCERT-VIP",
                SaleType.DROP,
                SaleEventStatus.LIVE,
                now.minusMinutes(10),
                now.plusDays(1),
                new BigDecimal("150000.00"),
                24,
                null,
                null
        );
    }

    private void seedSeller(String sellerId, String displayName, SellerVerificationStatus verificationStatus) {
        sellerProfileRepository.findById(sellerId).orElseGet(() -> sellerProfileRepository.save(SellerProfile.builder()
                .sellerId(sellerId)
                .displayName(displayName)
                .status(SellerStatus.ACTIVE)
                .verificationStatus(verificationStatus)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build()));
    }

    private void seedListing(String listingId, String sellerId, String productId, String title,
                             String description, String imageUrl, String itemCondition) {
        marketplaceListingRepository.findById(listingId).orElseGet(() -> marketplaceListingRepository.save(MarketplaceListing.builder()
                .listingId(listingId)
                .sellerId(sellerId)
                .productId(productId)
                .title(title)
                .description(description)
                .imageUrl(imageUrl)
                .itemCondition(itemCondition)
                .status(ListingStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build()));
    }

    private void seedSaleEvent(String saleEventId, String listingId, String sellerId, String productId,
                               SaleType saleType, SaleEventStatus status, LocalDateTime startsAt,
                               LocalDateTime endsAt, BigDecimal price, int stockQuantity,
                               BigDecimal minBidIncrement, BigDecimal reservePrice) {
        saleEventRepository.findById(saleEventId).orElseGet(() -> saleEventRepository.save(SaleEvent.builder()
                .saleEventId(saleEventId)
                .listingId(listingId)
                .sellerId(sellerId)
                .productId(productId)
                .saleType(saleType)
                .status(status)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .price(price)
                .stockQuantity(stockQuantity)
                .minBidIncrement(minBidIncrement)
                .reservePrice(reservePrice)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build()));
    }
}
