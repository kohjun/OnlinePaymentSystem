package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.WishlistItem;
import com.example.payment.domain.repository.SaleEventRepository;
import com.example.payment.domain.repository.WishlistItemRepository;
import com.example.payment.infrastructure.util.IdGenerator;
import com.example.payment.presentation.dto.response.MarketplaceEventResponse;
import com.example.payment.presentation.dto.response.WishlistItemResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 찜하기.
 *
 * 고객 식별자는 언제나 인증 신원에서 온다. 요청 본문이나 경로 변수로 받으면
 * 남의 찜 목록을 조작할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistItemRepository wishlistItemRepository;
    private final SaleEventRepository saleEventRepository;
    private final MarketplaceQueryService marketplaceQueryService;

    /**
     * 찜에 담는다. 이미 담겨 있으면 그대로 둔다.
     *
     * 두 번 담는 것은 오류가 아니라 이미 원하는 상태다. 하트를 연타하거나
     * 네트워크가 요청을 재전송해도 같은 결과여야 한다.
     */
    @Transactional
    public WishlistItemResponse add(String customerId, String saleEventId) {
        if (!saleEventRepository.existsById(saleEventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "판매 이벤트를 찾을 수 없습니다: " + saleEventId);
        }

        WishlistItem existing = wishlistItemRepository
                .findByCustomerIdAndSaleEventId(customerId, saleEventId)
                .orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        try {
            WishlistItem saved = wishlistItemRepository.save(WishlistItem.builder()
                    .wishlistItemId("WISH-" + IdGenerator.generateEventId())
                    .customerId(customerId)
                    .saleEventId(saleEventId)
                    .build());
            return toResponse(saved);
        } catch (DataIntegrityViolationException duplicateInsert) {
            // 동시에 두 번 담은 경우. 유니크 제약이 막아준 것이므로 이미
            // 담긴 항목을 돌려주면 된다.
            return wishlistItemRepository.findByCustomerIdAndSaleEventId(customerId, saleEventId)
                    .map(this::toResponse)
                    .orElseThrow(() -> duplicateInsert);
        }
    }

    /** 찜을 뺀다. 담겨 있지 않아도 오류로 보지 않는다. */
    @Transactional
    public boolean remove(String customerId, String saleEventId) {
        return wishlistItemRepository.deleteByCustomerIdAndSaleEventId(customerId, saleEventId) > 0;
    }

    /**
     * 내 찜 목록. 카드로 바로 그릴 수 있게 판매 이벤트 정보를 함께 채운다.
     *
     * 이벤트가 사라졌거나 비공개로 바뀐 항목은 event가 비어 나간다. 찜 기록
     * 자체는 남겨두고 화면에서 "판매가 종료된 상품"으로 표시하게 한다.
     */
    @Transactional(readOnly = true)
    public List<WishlistItemResponse> list(String customerId) {
        return wishlistItemRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(item -> {
                    MarketplaceEventResponse event = marketplaceQueryService
                            .getEvent(item.getSaleEventId())
                            .orElse(null);
                    return toResponse(item, event);
                })
                .toList();
    }

    /** 목록 화면에서 어떤 카드에 하트를 채울지 한 번에 판별한다. */
    @Transactional(readOnly = true)
    public Set<String> filterWishlisted(String customerId, List<String> saleEventIds) {
        if (saleEventIds == null || saleEventIds.isEmpty()) {
            return Set.of();
        }
        return wishlistItemRepository.findByCustomerIdAndSaleEventIdIn(customerId, saleEventIds).stream()
                .map(WishlistItem::getSaleEventId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private WishlistItemResponse toResponse(WishlistItem item) {
        return toResponse(item, null);
    }

    private WishlistItemResponse toResponse(WishlistItem item, MarketplaceEventResponse event) {
        return WishlistItemResponse.builder()
                .wishlistItemId(item.getWishlistItemId())
                .saleEventId(item.getSaleEventId())
                .createdAt(item.getCreatedAt())
                .event(event)
                .build();
    }
}
