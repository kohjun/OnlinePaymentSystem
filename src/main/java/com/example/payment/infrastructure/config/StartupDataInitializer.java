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
import com.example.payment.domain.repository.AuctionBidRepository;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.domain.repository.RaffleEntryRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.infrastructure.util.ResourceReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.distribution.mode", havingValue = "DEMO", matchIfMissing = true)
@RequiredArgsConstructor
public class StartupDataInitializer implements ApplicationRunner {

    private static final String CURATED = "SELLER-EVERYSALE-CURATED";
    private static final String LIVE_EVENTS = "SELLER-LIVE-EVENTS";
    private static final String MONO_ARCHIVE = "SELLER-MONO-ARCHIVE";
    private static final String SANDSTONE = "SELLER-SANDSTONE-LIVING";
    private static final String ANALOG_ROOM = "SELLER-ANALOG-ROOM";
    private static final String PLAIN_TECH = "SELLER-PLAIN-TECH";
    private static final String OAT_STUDIO = "SELLER-OAT-STUDIO";

    /**
     * 데모 마켓의 진열 상품. 사진은 static/catalog의 Unsplash 사진이다(docs/catalog-photo-credits.md).
     * 실제 브랜드명은 쓰지 않는다. 일반 판매와 한정 수량은 기간이 지나면 새 기간으로 열고,
     * 래플·경매는 응모·입찰 기록이 하나도 없을 때만 다시 연다. 기록이 있으면 추첨·낙찰·정산이
     * 이미 그 기간에 묶여 있다.
     */
    private static final List<DemoItem> CATALOG = List.of(
            new DemoItem("ES-KNIT-CREW", MONO_ARCHIVE, "FASHION", SaleType.FIXED_PRICE, "89000", 18, "NEW",
                    "knit-crewneck", "램스울 크루넥 니트 3컬러",
                    "부드러운 램스울 100%. 크림, 머스터드, 카멜 세 가지 컬러로 준비했습니다. 정사이즈를 권합니다.",
                    "니트,스웨터,램스울,가을"),
            new DemoItem("ES-KNIT-CARDIGAN", MONO_ARCHIVE, "FASHION", SaleType.FIXED_PRICE, "119000", 7, "LIKE_NEW",
                    "knit-cardigan", "케이블 니트 카디건 · 토프",
                    "두 번 착용한 케이블 니트 카디건입니다. 보풀 없이 보관했고 단추 모두 그대로입니다.",
                    "니트,카디건,중고"),
            new DemoItem("ES-BELTED-COAT", MONO_ARCHIVE, "FASHION", SaleType.FIXED_PRICE, "329000", 3, "LIKE_NEW",
                    "belted-coat", "울 블렌드 벨티드 코트 · 카멜",
                    "울 70% 블렌드 싱글 코트. 벨트 포함, 드라이클리닝 후 보관 중입니다.",
                    "코트,아우터,카멜,울"),
            new DemoItem("ES-LINEN-SHIRT", MONO_ARCHIVE, "FASHION", SaleType.FIXED_PRICE, "69000", 24, "NEW",
                    "linen-shirt", "린넨 헨리넥 셔츠 · 아이보리",
                    "워싱 린넨으로 자연스러운 주름이 살아 있는 헨리넥 셔츠입니다.",
                    "셔츠,린넨,여름"),
            new DemoItem("ES-LEATHER-TOTE", MONO_ARCHIVE, "FASHION", SaleType.DROP, "245000", 20, "NEW",
                    "leather-tote", "베지터블 레더 토트백 · 코냑 (20개 한정)",
                    "식물성 무두질 가죽으로 만든 토트백. 쓸수록 색이 깊어집니다. 20개만 제작했습니다.",
                    "가방,토트백,가죽,한정"),
            new DemoItem("ES-CHELSEA-BOOTS", MONO_ARCHIVE, "FASHION", SaleType.FIXED_PRICE, "198000", 5, "GOOD",
                    "chelsea-boots", "스웨이드 첼시 부츠 · 탄",
                    "한 시즌 신은 스웨이드 첼시 부츠입니다. 밑창 마모 적고 방수 스프레이 처리했습니다.",
                    "부츠,신발,스웨이드"),
            new DemoItem("ES-PENNY-LOAFERS", MONO_ARCHIVE, "FASHION", SaleType.FIXED_PRICE, "159000", 6, "LIKE_NEW",
                    "penny-loafers", "레더 페니 로퍼 · 브라운",
                    "실내 착용만 한 페니 로퍼입니다. 슈트리와 더스트백을 함께 보내드립니다.",
                    "로퍼,구두,신발"),
            new DemoItem("ES-SILVER-BRACELET", OAT_STUDIO, "FASHION", SaleType.FIXED_PRICE, "58000", 15, "NEW",
                    "silver-bracelet", "925 실버 체인 브레이슬릿",
                    "가는 체인을 두 줄로 겹친 925 실버 브레이슬릿. 길이 조절 가능합니다.",
                    "팔찌,주얼리,실버"),
            new DemoItem("ES-HEADPHONES", PLAIN_TECH, "ELECTRONICS", SaleType.FIXED_PRICE, "489000", 2, "LIKE_NEW",
                    "headphones", "무선 노이즈캔슬링 헤드폰 · 실버",
                    "구매 4개월, 배터리 성능 양호. 정품 케이스와 케이블 포함입니다.",
                    "헤드폰,노이즈캔슬링,음향"),
            new DemoItem("ES-MIRRORLESS", PLAIN_TECH, "ELECTRONICS", SaleType.FIXED_PRICE, "1190000", 1, "GOOD",
                    "mirrorless-camera", "풀프레임 미러리스 바디 · 셔터 1.2만 회",
                    "센서 클리닝 완료. 배터리 2개와 충전기를 함께 드립니다. 렌즈는 미포함입니다.",
                    "카메라,미러리스,풀프레임"),
            new DemoItem("ES-RETRO-KEYBOARD", PLAIN_TECH, "ELECTRONICS", SaleType.DROP, "149000", 40, "NEW",
                    "retro-keyboard", "레트로 75% 기계식 키보드 · 크림",
                    "PBT 키캡과 저소음 리니어 스위치. 40대 한정 생산분입니다.",
                    "키보드,기계식,한정"),
            new DemoItem("ES-BOOKSHELF-SPEAKER", PLAIN_TECH, "ELECTRONICS", SaleType.FIXED_PRICE, "279000", 3, "LIKE_NEW",
                    "bookshelf-speaker", "북쉘프 스피커 페어 · 월넛",
                    "월넛 무늬목 패시브 스피커 한 쌍. 외관 생활기스 거의 없습니다.",
                    "스피커,오디오,음향"),
            new DemoItem("ES-TABLET", PLAIN_TECH, "ELECTRONICS", SaleType.FIXED_PRICE, "620000", 2, "LIKE_NEW",
                    "tablet", "11인치 태블릿 · 256GB, 펜 포함",
                    "강화유리 부착 상태로 사용했습니다. 펜과 정품 충전기를 함께 드립니다.",
                    "태블릿,펜,중고"),
            new DemoItem("ES-SMARTWATCH", PLAIN_TECH, "ELECTRONICS", SaleType.FIXED_PRICE, "289000", 4, "GOOD",
                    "smartwatch", "스마트워치 41mm · 화이트 밴드",
                    "배터리 효율 92%. 화면 보호필름을 붙여 사용해 액정 상태가 좋습니다.",
                    "스마트워치,웨어러블"),
            new DemoItem("ES-HANDMADE-CUP", OAT_STUDIO, "HOME_LIVING", SaleType.FIXED_PRICE, "32000", 30, "NEW",
                    "handmade-cup", "핸드메이드 도자기 컵 · 샌드 글레이즈",
                    "물레로 빚어 하나씩 유약을 입힌 컵입니다. 모양과 색이 조금씩 다릅니다.",
                    "컵,도자기,핸드메이드"),
            new DemoItem("ES-LINEN-BEDDING", SANDSTONE, "HOME_LIVING", SaleType.FIXED_PRICE, "189000", 8, "NEW",
                    "linen-bedding", "워싱 린넨 침구 세트 · 퀸 · 오트밀",
                    "이불 커버, 베개 커버 2장 구성. 프렌치 린넨 100%입니다.",
                    "침구,린넨,침실"),
            new DemoItem("ES-PAPER-LAMP", SANDSTONE, "HOME_LIVING", SaleType.FIXED_PRICE, "219000", 5, "NEW",
                    "paper-floor-lamp", "페이퍼 플로어 조명 · 120cm",
                    "한지 갓을 씌운 플로어 조명. 은은한 확산광으로 침실과 거실에 어울립니다.",
                    "조명,플로어램프,인테리어"),
            new DemoItem("ES-BOUCLE-CHAIR", SANDSTONE, "HOME_LIVING", SaleType.FIXED_PRICE, "459000", 2, "NEW",
                    "boucle-lounge-chair", "부클레 라운지 체어 · 로즈베이지",
                    "부클레 원단과 원목 다리의 라운지 체어. 착석감이 깊고 부드럽습니다.",
                    "의자,라운지체어,가구"),
            new DemoItem("ES-POUR-OVER", OAT_STUDIO, "HOME_LIVING", SaleType.FIXED_PRICE, "76000", 15, "NEW",
                    "pour-over-set", "핸드드립 커피 세트 · 드리퍼와 서버",
                    "세라믹 드리퍼와 유리 서버, 필터 100매 구성입니다.",
                    "커피,드리퍼,주방"),
            new DemoItem("ES-PILLAR-CANDLE", OAT_STUDIO, "HOME_LIVING", SaleType.FIXED_PRICE, "38000", 25, "NEW",
                    "pillar-candles", "필라 캔들 3종 세트 · 무향",
                    "소이 왁스 필라 캔들 세 가지 높이. 그을음이 적습니다.",
                    "캔들,인테리어,선물"),
            new DemoItem("ES-OAK-CHAIR", ANALOG_ROOM, "HOME_LIVING", SaleType.AUCTION, "180000", 1, "GOOD",
                    "oak-dining-chair", "오크 원목 다이닝 체어 · 1970년대",
                    "북유럽 빈티지 오크 체어. 접합부 보강과 오일 마감을 새로 했습니다.",
                    "의자,빈티지,원목,경매"),
            new DemoItem("ES-DONUT-VASE", OAT_STUDIO, "HOME_LIVING", SaleType.FIXED_PRICE, "58000", 12, "NEW",
                    "donut-vase", "도넛 세라믹 화병 · 베이지",
                    "가운데가 뚫린 도넛 형태의 무광 세라믹 화병. 드라이플라워와 잘 어울립니다.",
                    "화병,세라믹,오브제"),
            new DemoItem("ES-CERAMIC-OBJECTS", ANALOG_ROOM, "COLLECTIBLE", SaleType.FIXED_PRICE, "240000", 1, "GOOD",
                    "ceramic-objects", "빈티지 세라믹 오브제 5점 세트",
                    "작가 미상 빈티지 도자기 다섯 점. 이 빠짐 없이 보관한 상태입니다.",
                    "도자기,빈티지,오브제"),
            new DemoItem("ES-FILM-CAMERA", ANALOG_ROOM, "COLLECTIBLE", SaleType.FIXED_PRICE, "340000", 1, "GOOD",
                    "film-camera", "35mm 필름 카메라 · 가죽 스트랩",
                    "셔터 속도와 노출계 작동을 확인했습니다. 시험 촬영한 필름 스캔본을 함께 드립니다.",
                    "필름카메라,빈티지,카메라"),
            new DemoItem("ES-AMBER-VINYL", ANALOG_ROOM, "COLLECTIBLE", SaleType.DROP, "52000", 50, "NEW",
                    "amber-vinyl", "재즈 컴필레이션 LP · 앰버 컬러반",
                    "180g 앰버 컬러 바이널, 넘버링 포함 50장 한정 발매입니다.",
                    "LP,바이널,재즈,한정"),
            new DemoItem("ES-HAND-CREAM", OAT_STUDIO, "BEAUTY", SaleType.FIXED_PRICE, "36000", 40, "NEW",
                    "hand-cream", "시어버터 핸드크림 3종 기프트 세트",
                    "무화과, 베티버, 무향 세 가지 향의 핸드크림 50ml 세트입니다.",
                    "핸드크림,선물,뷰티")
    );

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final ResourceReservationService resourceReservationService;
    private final SellerProfileRepository sellerProfileRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final SaleEventRepository saleEventRepository;
    private final RaffleEntryRepository raffleEntryRepository;
    private final AuctionBidRepository auctionBidRepository;

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
        seedProduct("CONCERT-VIP", "Starlight World Tour - VIP Seat", "Concert VIP Ticket",
                new BigDecimal("150000.00"), "CONCERT", 24);
        seedProduct("CONCERT-R", "Starlight World Tour - R Seat", "Concert R Ticket",
                new BigDecimal("120000.00"), "CONCERT", 36);
        seedProduct("CONCERT-S", "Starlight World Tour - S Seat", "Concert S Ticket",
                new BigDecimal("90000.00"), "CONCERT", 48);
        seedProduct("DRAW-NIKE", "넘버링 아트 프린트 10점 한정 래플", "Limited edition art print raffle",
                new BigDecimal("239000.00"), "COLLECTIBLE", 10);
        seedProduct("AUCTION-ROLEX", "1950년대 이안 리플렉스 카메라 경매", "Vintage twin-lens reflex camera",
                new BigDecimal("380000.00"), "COLLECTIBLE", 1);
        CATALOG.forEach(item -> seedProduct(item.productId(), item.title(), item.description(),
                new BigDecimal(item.price()), item.category(), item.quantity()));

        refreshLegacyDemoCopy();
        seedMarketplaceCatalog();

        List<String> inventoryKeys = new ArrayList<>(List.of("PROD-001", "PROD-002", "SAGA-TEST-001",
                "CONCURRENCY-TEST-001", "DB-TEST-001", "PHASE2-TEST-001", "CONCERT-VIP", "CONCERT-R", "CONCERT-S",
                "DRAW-NIKE", "AUCTION-ROLEX"));
        CATALOG.forEach(item -> inventoryKeys.add(item.productId()));
        inventoryKeys.forEach(productId ->
                inventoryRepository.findById(productId).ifPresent(inventory ->
                        initializeRedisInventoryIfMissing(
                                productId,
                                inventory.getTotalQuantity(),
                                inventory.getAvailableQuantity()
                        )));
    }

    private void initializeRedisInventoryIfMissing(String productId, int total, int available) {
        String resourceKey = "inventory:" + productId;
        try {
            Map<String, Object> current = resourceReservationService.getResourceStatus(resourceKey);
            if (current.isEmpty()) {
                resourceReservationService.initializeResource(resourceKey, total, available);
                return;
            }
            log.info("Redis inventory already exists, skipping initialization: key={}", resourceKey);
        } catch (RuntimeException redisUnavailable) {
            log.warn("Redis inventory initialization deferred to reconciliation: key={}, reason={}",
                    resourceKey, redisUnavailable.getMessage());
        }
    }

    private void seedProduct(String id, String name, String description,
                             BigDecimal price, String category, int quantity) {
        productRepository.findById(id).orElseGet(() -> productRepository.save(Product.builder()
                .id(id)
                .name(name)
                .description(description)
                .price(price)
                .category(category)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build()));

        inventoryRepository.findById(id).orElseGet(() -> inventoryRepository.save(Inventory.builder()
                .productId(id)
                .totalQuantity(quantity)
                .availableQuantity(quantity)
                .reservedQuantity(0)
                .lastUpdatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build()));

        log.info("Seed data ready: productId={}, quantity={}", id, quantity);
    }

    private void seedMarketplaceCatalog() {
        seedSeller(CURATED, "에브리세일 셀렉트", SellerVerificationStatus.VERIFIED);
        seedSeller(LIVE_EVENTS, "스테이지원 티켓", SellerVerificationStatus.VERIFIED);
        seedSeller(MONO_ARCHIVE, "모노 아카이브", SellerVerificationStatus.VERIFIED);
        seedSeller(SANDSTONE, "샌드스톤 리빙", SellerVerificationStatus.VERIFIED);
        seedSeller(ANALOG_ROOM, "아날로그 룸", SellerVerificationStatus.VERIFIED);
        seedSeller(PLAIN_TECH, "플레인 테크", SellerVerificationStatus.VERIFIED);
        seedSeller(OAT_STUDIO, "오트 스튜디오", SellerVerificationStatus.VERIFIED);

        seedListing(
                "LIST-DRAW-NIKE",
                CURATED,
                "DRAW-NIKE",
                "넘버링 아트 프린트 · 10점 한정 래플",
                "작가 서명과 넘버링이 들어간 지클레 프린트입니다. 응모자 중 10명을 추첨합니다.",
                "/catalog/art-print.jpg",
                "NEW",
                "아트프린트,래플,한정,액자"
        );
        seedListing(
                "LIST-AUCTION-ROLEX",
                CURATED,
                "AUCTION-ROLEX",
                "1950년대 이안 리플렉스 카메라 · 실시간 경매",
                "셔터와 거울 작동을 확인한 이안 리플렉스 카메라입니다. 검수 후 출고합니다.",
                "/catalog/tlr-camera.jpg",
                "PRE_OWNED",
                "필름카메라,빈티지,경매"
        );
        seedListing(
                "LIST-CONCERT-VIP",
                LIVE_EVENTS,
                "CONCERT-VIP",
                "스타라이트 월드투어 서울 · VIP석",
                "VIP 좌석을 선착순으로 판매합니다. 결제 후 모바일 티켓이 바로 발급됩니다.",
                "/concert_poster.png",
                "DIGITAL_TICKET",
                "콘서트,티켓,VIP"
        );
        CATALOG.forEach(item -> seedListing(item.listingId(), item.sellerId(), item.productId(), item.title(),
                item.description(), "/catalog/" + item.image() + ".jpg", item.condition(), item.tags()));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        seedSaleEvent(
                "EVT-DRAW-NIKE",
                "LIST-DRAW-NIKE",
                CURATED,
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
        reopenIfUntouched("EVT-DRAW-NIKE", now.minusHours(1), now.plusDays(7));
        seedSaleEvent(
                "EVT-AUCTION-ROLEX",
                "LIST-AUCTION-ROLEX",
                CURATED,
                "AUCTION-ROLEX",
                SaleType.AUCTION,
                SaleEventStatus.LIVE,
                now.minusMinutes(30),
                now.plusHours(6),
                new BigDecimal("380000.00"),
                1,
                new BigDecimal("10000.00"),
                new BigDecimal("380000.00")
        );
        reopenIfUntouched("EVT-AUCTION-ROLEX", now.minusMinutes(30), now.plusDays(2));
        seedSaleEvent(
                "EVT-CONCERT-VIP",
                "LIST-CONCERT-VIP",
                LIVE_EVENTS,
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
        refreshExpiredDemoEvent(
                "EVT-CONCERT-VIP",
                now.minusMinutes(10),
                now.plusDays(1)
        );

        for (int index = 0; index < CATALOG.size(); index++) {
            DemoItem item = CATALOG.get(index);
            // 정렬이 의미 있게 보이도록 시작 시각을 조금씩 어긋나게 둔다.
            LocalDateTime startsAt = now.minusHours(2L + index * 5L);
            LocalDateTime endsAt = switch (item.saleType()) {
                case DROP -> now.plusDays(3);
                case AUCTION -> now.plusDays(2);
                default -> now.plusDays(30);
            };
            boolean auction = item.saleType() == SaleType.AUCTION;
            seedSaleEvent(item.saleEventId(), item.listingId(), item.sellerId(), item.productId(),
                    item.saleType(), SaleEventStatus.LIVE, startsAt, endsAt, new BigDecimal(item.price()),
                    item.quantity(),
                    auction ? new BigDecimal("10000.00") : null,
                    auction ? new BigDecimal(item.price()) : null);
            if (auction) {
                reopenIfUntouched(item.saleEventId(), startsAt, endsAt);
            } else {
                refreshExpiredDemoEvent(item.saleEventId(), startsAt, endsAt);
            }
        }
    }

    /**
     * 예전 시드는 실제 브랜드와 아티스트 이름을 썼다. ID는 통합 테스트가 쓰므로 그대로 두고,
     * 예전 문구가 남아 있을 때만 바꾼다. 운영자가 고친 값은 덮어쓰지 않는다.
     */
    private void refreshLegacyDemoCopy() {
        replaceLegacySeller(CURATED, "EverySale Curated", "에브리세일 셀렉트");
        replaceLegacySeller(LIVE_EVENTS, "Live Events Korea", "스테이지원 티켓");

        replaceLegacyProduct("DRAW-NIKE", "나이키 에어 조던 1 레트로 한정판 드로우",
                "넘버링 아트 프린트 10점 한정 래플", "COLLECTIBLE");
        replaceLegacyProduct("AUCTION-ROLEX", "빈티지 롤렉스 서브마리너 경매",
                "1950년대 이안 리플렉스 카메라 경매", "COLLECTIBLE");
        replaceLegacyProduct("CONCERT-VIP", "PSY Summer Swag - VIP Seat", "Starlight World Tour - VIP Seat", "CONCERT");
        replaceLegacyProduct("CONCERT-R", "PSY Summer Swag - R Seat", "Starlight World Tour - R Seat", "CONCERT");
        replaceLegacyProduct("CONCERT-S", "PSY Summer Swag - S Seat", "Starlight World Tour - S Seat", "CONCERT");

        replaceLegacyListing("LIST-DRAW-NIKE", "나이키 에어 조던 1 레트로 한정판 드로우",
                "넘버링 아트 프린트 · 10점 한정 래플",
                "작가 서명과 넘버링이 들어간 지클레 프린트입니다. 응모자 중 10명을 추첨합니다.",
                "/catalog/art-print.jpg");
        replaceLegacyListing("LIST-AUCTION-ROLEX", "빈티지 롤렉스 서브마리너 실시간 경매",
                "1950년대 이안 리플렉스 카메라 · 실시간 경매",
                "셔터와 거울 작동을 확인한 이안 리플렉스 카메라입니다. 검수 후 출고합니다.",
                "/catalog/tlr-camera.jpg");
        replaceLegacyListing("LIST-CONCERT-VIP", "PSY Summer Swag VIP 드롭",
                "스타라이트 월드투어 서울 · VIP석",
                "VIP 좌석을 선착순으로 판매합니다. 결제 후 모바일 티켓이 바로 발급됩니다.",
                "/concert_poster.png");
    }

    private void replaceLegacySeller(String sellerId, String legacyName, String displayName) {
        sellerProfileRepository.findById(sellerId)
                .filter(seller -> legacyName.equals(seller.getDisplayName()))
                .ifPresent(seller -> {
                    seller.setDisplayName(displayName);
                    seller.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    sellerProfileRepository.save(seller);
                });
    }

    private void replaceLegacyProduct(String productId, String legacyName, String name, String category) {
        productRepository.findById(productId)
                .filter(product -> legacyName.equals(product.getName()))
                .ifPresent(product -> {
                    product.setName(name);
                    product.setCategory(category);
                    product.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    productRepository.save(product);
                });
    }

    private void replaceLegacyListing(String listingId, String legacyTitle, String title,
                                      String description, String imageUrl) {
        marketplaceListingRepository.findById(listingId)
                .filter(listing -> legacyTitle.equals(listing.getTitle()))
                .ifPresent(listing -> {
                    listing.setTitle(title);
                    listing.setDescription(description);
                    listing.setImageUrl(imageUrl);
                    listing.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    marketplaceListingRepository.save(listing);
                });
    }

    private void seedSeller(String sellerId, String displayName, SellerVerificationStatus verificationStatus) {
        sellerProfileRepository.findById(sellerId).orElseGet(() -> sellerProfileRepository.save(SellerProfile.builder()
                .sellerId(sellerId)
                .displayName(displayName)
                .status(SellerStatus.ACTIVE)
                .verificationStatus(verificationStatus)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build()));
    }

    private void seedListing(String listingId, String sellerId, String productId, String title,
                             String description, String imageUrl, String itemCondition, String tags) {
        marketplaceListingRepository.findById(listingId).orElseGet(() -> marketplaceListingRepository.save(MarketplaceListing.builder()
                .listingId(listingId)
                .sellerId(sellerId)
                .productId(productId)
                .title(title)
                .description(description)
                .imageUrl(imageUrl)
                .itemCondition(itemCondition)
                .tags(tags)
                .status(ListingStatus.ACTIVE)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
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
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build()));
    }

    private void reopenIfUntouched(String saleEventId, LocalDateTime startsAt, LocalDateTime endsAt) {
        boolean untouched = saleEventRepository.findById(saleEventId)
                .map(event -> event.getSaleType() == SaleType.RAFFLE
                        ? raffleEntryRepository.countBySaleEventId(saleEventId) == 0
                        : auctionBidRepository.findFirstBySaleEventIdOrderByBidAmountDescCreatedAtAsc(saleEventId).isEmpty())
                .orElse(false);
        if (untouched) {
            refreshExpiredDemoEvent(saleEventId, startsAt, endsAt);
        }
    }

    private void refreshExpiredDemoEvent(String saleEventId,
                                         LocalDateTime startsAt,
                                         LocalDateTime endsAt) {
        saleEventRepository.findById(saleEventId).ifPresent(event -> {
            boolean expired = event.getEndsAt() != null
                    && !event.getEndsAt().isAfter(LocalDateTime.now(ZoneOffset.UTC));
            if (expired || event.getStatus() == SaleEventStatus.ENDED) {
                event.setStatus(SaleEventStatus.LIVE);
                event.setCloseReason(null);
                event.setStartsAt(startsAt);
                event.setEndsAt(endsAt);
                event.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                saleEventRepository.save(event);
                log.info("Refreshed expired demo event: saleEventId={}, endsAt={}", saleEventId, endsAt);
            }
        });
    }

    private record DemoItem(String productId, String sellerId, String category, SaleType saleType,
                            String price, int quantity, String condition, String image,
                            String title, String description, String tags) {

        String listingId() {
            return "LIST-" + productId;
        }

        String saleEventId() {
            return "EVT-" + productId;
        }
    }
}
