package com.example.payment.infrastructure.search;

import com.example.payment.application.service.MarketplaceQueryService;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SaleEventStatus;
import com.example.payment.domain.model.marketplace.SaleType;
import com.example.payment.domain.repository.InventoryRepository;
import com.example.payment.domain.repository.MarketplaceListingRepository;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검색 색인이 있을 때와 없을 때의 경로 선택.
 *
 * 이 프로젝트에서 색인은 있으면 좋은 것이지 없으면 안 되는 것이 아니다.
 * 색인이 죽었을 때 상품 목록이 비어 보이면 검색 도입이 오히려 장애 원인이
 * 되므로, 폴백이 실제로 도는지 고정해 둔다.
 */
class SaleEventSearchFallbackTest {

    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceListingRepository listingRepository = mock(MarketplaceListingRepository.class);
    private final SellerProfileRepository sellerRepository = mock(SellerProfileRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    private final SaleEventSearchIndex searchIndex = mock(SaleEventSearchIndex.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<SaleEventSearchIndex> searchIndexProvider = mock(ObjectProvider.class);

    private final MarketplaceQueryService service = new MarketplaceQueryService(
            saleEventRepository, listingRepository, sellerRepository,
            productRepository, inventoryRepository, searchIndexProvider);

    private void stubEmptyDatabaseResults() {
        when(saleEventRepository.searchPublicEvents(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(24), 0));
        when(saleEventRepository.findPublicEventsByIds(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(24), 0));
        when(listingRepository.findAllById(any())).thenReturn(List.of());
        when(productRepository.findAllById(any())).thenReturn(List.of());
        when(sellerRepository.findAllById(any())).thenReturn(List.of());
        when(inventoryRepository.findAllById(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("색인이 꺼져 있으면 기존 DB 키워드 질의를 쓴다")
    void withoutIndexTheDatabaseQueryIsUsed() {
        when(searchIndexProvider.getIfAvailable()).thenReturn(null);
        stubEmptyDatabaseResults();

        service.getEventsPage(null, null, "스니커즈", "startsAt", 0, 24);

        verify(saleEventRepository).searchPublicEvents(any(), any(), anyString(), any());
        verify(saleEventRepository, never()).findPublicEventsByIds(any(), any(), any(), any());
    }

    @Test
    @DisplayName("색인이 후보를 주면 그 식별자로 공개 조건을 다시 확인한다")
    void indexCandidatesAreRecheckedAgainstTheDatabase() {
        when(searchIndexProvider.getIfAvailable()).thenReturn(searchIndex);
        when(searchIndex.findEventIds("스니커즈")).thenReturn(Optional.of(List.of("EVT-1", "EVT-2")));
        stubEmptyDatabaseResults();

        service.getEventsPage(null, null, "스니커즈", "startsAt", 0, 24);

        // 색인이 뒤처져 이미 내려간 상품을 줘도 DB 조건에서 걸러진다.
        verify(saleEventRepository).findPublicEventsByIds(any(), any(), eq(List.of("EVT-1", "EVT-2")), any());
        verify(saleEventRepository, never()).searchPublicEvents(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("색인이 응답하지 않으면 DB 질의로 되돌아간다")
    void indexFailureFallsBackToTheDatabase() {
        when(searchIndexProvider.getIfAvailable()).thenReturn(searchIndex);
        // 색인 장애는 빈 Optional로 표현된다.
        when(searchIndex.findEventIds("스니커즈")).thenReturn(Optional.empty());
        stubEmptyDatabaseResults();

        service.getEventsPage(null, null, "스니커즈", "startsAt", 0, 24);

        verify(saleEventRepository).searchPublicEvents(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("색인이 결과 없음을 알려주면 DB를 다시 뒤지지 않는다")
    void emptyIndexResultIsNotAFallbackTrigger() {
        when(searchIndexProvider.getIfAvailable()).thenReturn(searchIndex);
        // 빈 목록은 "맞는 상품이 없다"는 뜻이지 장애가 아니다.
        when(searchIndex.findEventIds("없는상품")).thenReturn(Optional.of(List.of()));
        stubEmptyDatabaseResults();

        service.getEventsPage(null, null, "없는상품", "startsAt", 0, 24);

        verify(saleEventRepository, never()).searchPublicEvents(any(), any(), anyString(), any());
        verify(saleEventRepository, never()).findPublicEventsByIds(any(), any(), any(), any());
    }

    @Test
    @DisplayName("키워드가 없으면 색인을 부르지 않는다")
    void browsingWithoutKeywordSkipsTheIndex() {
        when(searchIndexProvider.getIfAvailable()).thenReturn(searchIndex);
        stubEmptyDatabaseResults();

        service.getEventsPage(null, null, null, "startsAt", 0, 24);

        verify(searchIndex, never()).findEventIds(any());
        verify(saleEventRepository).searchPublicEvents(any(), any(), eq(null), any());
    }
}
