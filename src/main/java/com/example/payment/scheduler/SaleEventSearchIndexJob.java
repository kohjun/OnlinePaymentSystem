package com.example.payment.scheduler;

import com.example.payment.domain.model.marketplace.MarketplaceListing;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.infrastructure.search.SaleEventDocument;
import com.example.payment.infrastructure.search.SaleEventSearchIndex;
import com.example.payment.infrastructure.search.SaleEventSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공개 판매 이벤트를 주기적으로 색인한다.
 *
 * 쓰기 경로마다 색인 갱신을 끼워 넣는 대신 주기적으로 전체를 맞춘다.
 * 카탈로그가 작고, 색인이 잠시 뒤처져도 검색 결과가 조금 늦게 반영될 뿐
 * 거래에는 영향이 없기 때문이다. 가격·재고 같은 값은 애초에 색인에 넣지
 * 않고 DB에서 읽으므로, 뒤처진 색인이 잘못된 정보를 보여주지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class SaleEventSearchIndexJob {

    private final SaleEventRepository saleEventRepository;
    private final MarketplaceListingRepository marketplaceListingRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final SaleEventSearchIndex searchIndex;
    private final SaleEventSearchProperties properties;

    @Scheduled(
            fixedDelayString = "${app.search.reindex-fixed-delay-ms:300000}",
            initialDelayString = "${app.search.reindex-initial-delay-ms:20000}"
    )
    public void reindex() {
        try {
            int indexed = reindexNow();
            log.debug("Sale event search index refreshed: documents={}", indexed);
        } catch (RuntimeException e) {
            log.error("Sale event search reindex failed", e);
        }
    }

    @Transactional(readOnly = true)
    public int reindexNow() {
        List<SaleEvent> events = saleEventRepository.findByStatusInOrderByStartsAtAsc(
                List.of(SaleEventStatus.LIVE, SaleEventStatus.SCHEDULED));

        Map<String, MarketplaceListing> listings = marketplaceListingRepository
                .findAllById(events.stream().map(SaleEvent::getListingId).distinct().toList())
                .stream().collect(Collectors.toMap(MarketplaceListing::getListingId, Function.identity()));
        Map<String, SellerProfile> sellers = sellerProfileRepository
                .findAllById(events.stream().map(SaleEvent::getSellerId).distinct().toList())
                .stream().collect(Collectors.toMap(SellerProfile::getSellerId, Function.identity()));

        List<SaleEventDocument> documents = events.stream()
                .map(event -> toDocument(event, listings.get(event.getListingId()), sellers.get(event.getSellerId())))
                .filter(document -> document != null)
                .toList();

        return searchIndex.replaceAll(documents) ? documents.size() : 0;
    }

    private SaleEventDocument toDocument(SaleEvent event, MarketplaceListing listing, SellerProfile seller) {
        if (listing == null) {
            return null;
        }
        return SaleEventDocument.builder()
                .saleEventId(event.getSaleEventId())
                .title(listing.getTitle())
                .description(listing.getDescription())
                .sellerName(seller == null ? null : seller.getDisplayName())
                .brand(listing.getBrand())
                .tags(listing.getTags())
                .build();
    }

    /** 재색인 주기 설정을 로그로 남겨 운영 중 확인할 수 있게 한다. */
    public long configuredDelayMs() {
        return properties.getReindexFixedDelayMs();
    }
}
