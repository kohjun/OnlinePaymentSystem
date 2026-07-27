package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.WishlistItem;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.WishlistItemRepository;
import com.example.payment.presentation.dto.response.MarketplaceEventResponse;
import com.example.payment.presentation.dto.response.WishlistItemResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WishlistServiceTest {

    private final WishlistItemRepository wishlistItemRepository = mock(WishlistItemRepository.class);
    private final SaleEventRepository saleEventRepository = mock(SaleEventRepository.class);
    private final MarketplaceQueryService marketplaceQueryService = mock(MarketplaceQueryService.class);

    private final WishlistService service = new WishlistService(
            wishlistItemRepository, saleEventRepository, marketplaceQueryService);

    private WishlistItem item(String saleEventId) {
        return WishlistItem.builder()
                .wishlistItemId("WISH-1")
                .customerId("CUST-1")
                .saleEventId(saleEventId)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("찜에 담으면 항목이 만들어진다")
    void addCreatesItem() {
        when(saleEventRepository.existsById("EVT-1")).thenReturn(true);
        when(wishlistItemRepository.findByCustomerIdAndSaleEventId("CUST-1", "EVT-1"))
                .thenReturn(Optional.empty());
        when(wishlistItemRepository.save(any(WishlistItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WishlistItemResponse result = service.add("CUST-1", "EVT-1");

        assertEquals("EVT-1", result.getSaleEventId());
        assertNotNull(result.getWishlistItemId());
    }

    @Test
    @DisplayName("이미 담긴 상품을 다시 담아도 오류가 아니다")
    void addIsIdempotent() {
        when(saleEventRepository.existsById("EVT-1")).thenReturn(true);
        when(wishlistItemRepository.findByCustomerIdAndSaleEventId("CUST-1", "EVT-1"))
                .thenReturn(Optional.of(item("EVT-1")));

        WishlistItemResponse result = service.add("CUST-1", "EVT-1");

        assertEquals("WISH-1", result.getWishlistItemId());
        // 하트를 연타해도 새 행이 생기면 안 된다.
        verify(wishlistItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("동시에 담아 유니크 제약에 걸려도 이미 담긴 항목을 돌려준다")
    void concurrentAddResolvesToExistingItem() {
        when(saleEventRepository.existsById("EVT-1")).thenReturn(true);
        when(wishlistItemRepository.findByCustomerIdAndSaleEventId("CUST-1", "EVT-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(item("EVT-1")));
        when(wishlistItemRepository.save(any(WishlistItem.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        WishlistItemResponse result = service.add("CUST-1", "EVT-1");

        assertEquals("WISH-1", result.getWishlistItemId());
    }

    @Test
    @DisplayName("존재하지 않는 판매 이벤트는 담을 수 없다")
    void addRejectsUnknownEvent() {
        when(saleEventRepository.existsById("EVT-MISSING")).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.add("CUST-1", "EVT-MISSING"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(wishlistItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("담겨 있지 않은 상품을 빼도 오류가 아니다")
    void removeIsIdempotent() {
        when(wishlistItemRepository.deleteByCustomerIdAndSaleEventId("CUST-1", "EVT-1")).thenReturn(0L);

        assertFalse(service.remove("CUST-1", "EVT-1"));
    }

    @Test
    @DisplayName("찜 목록은 카드로 그릴 수 있게 판매 이벤트를 함께 채운다")
    void listFillsEventDetails() {
        when(wishlistItemRepository.findByCustomerIdOrderByCreatedAtDesc("CUST-1"))
                .thenReturn(List.of(item("EVT-1")));
        MarketplaceEventResponse event = MarketplaceEventResponse.builder()
                .saleEventId("EVT-1")
                .title("한정판 스니커즈")
                .build();
        when(marketplaceQueryService.getEvent("EVT-1")).thenReturn(Optional.of(event));

        List<WishlistItemResponse> result = service.list("CUST-1");

        assertEquals(1, result.size());
        assertEquals("한정판 스니커즈", result.get(0).getEvent().getTitle());
    }

    @Test
    @DisplayName("판매가 끝난 상품도 찜 기록은 남기고 이벤트만 비운다")
    void listKeepsItemWhenEventIsGone() {
        when(wishlistItemRepository.findByCustomerIdOrderByCreatedAtDesc("CUST-1"))
                .thenReturn(List.of(item("EVT-GONE")));
        when(marketplaceQueryService.getEvent("EVT-GONE")).thenReturn(Optional.empty());

        List<WishlistItemResponse> result = service.list("CUST-1");

        assertEquals(1, result.size(), "이벤트가 사라져도 찜 기록은 남아야 합니다");
        assertNull(result.get(0).getEvent());
        assertEquals("EVT-GONE", result.get(0).getSaleEventId());
    }

    @Test
    @DisplayName("목록 화면용 찜 여부 판별은 담긴 것만 돌려준다")
    void filterWishlistedReturnsOnlyMatches() {
        when(wishlistItemRepository.findByCustomerIdAndSaleEventIdIn(anyString(), any()))
                .thenReturn(List.of(item("EVT-1")));

        Set<String> result = service.filterWishlisted("CUST-1", List.of("EVT-1", "EVT-2"));

        assertTrue(result.contains("EVT-1"));
        assertFalse(result.contains("EVT-2"));
    }

    @Test
    @DisplayName("빈 목록을 물으면 조회하지 않는다")
    void filterWishlistedSkipsEmptyInput() {
        assertTrue(service.filterWishlisted("CUST-1", List.of()).isEmpty());

        verify(wishlistItemRepository, never()).findByCustomerIdAndSaleEventIdIn(anyString(), any());
    }
}
