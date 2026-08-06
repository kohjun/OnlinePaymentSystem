package com.example.payment.application.service;

import com.example.payment.domain.model.marketplace.MarketplaceOrderStatus;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.model.marketplace.SellerProfile;
import com.example.payment.domain.repository.MarketplaceOrderRepository;
import com.example.payment.domain.repository.SellerProfileRepository;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import com.example.payment.infrastructure.security.CommerceActor;
import com.example.payment.infrastructure.security.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CommercePolicyService {

    private static final Set<String> ACTIVE_INTENT_STATUSES =
            Set.of("READY", "AUTHENTICATED", "PENDING", "UNKNOWN");
    private static final Set<MarketplaceOrderStatus> COUNTED_ORDER_STATUSES =
            Set.of(MarketplaceOrderStatus.PAID, MarketplaceOrderStatus.PARTIALLY_REFUNDED);

    private final SellerProfileRepository sellerProfileRepository;
    private final MarketplaceOrderRepository marketplaceOrderRepository;
    private final TossPaymentIntentRepository tossPaymentIntentRepository;
    private final SecurityAuditService securityAuditService;

    public void requireTradeAllowed(SaleEvent event, CommerceActor actor, String action) {
        SellerProfile seller = sellerProfileRepository.findById(event.getSellerId())
                .orElseThrow(() -> new MarketplaceCheckoutException(
                        HttpStatus.CONFLICT, "SELLER_NOT_FOUND", "판매자 정보를 확인할 수 없습니다."));
        boolean sameUser = hasText(actor.userId()) && actor.userId().equals(seller.getOwnerUserId());
        boolean sameCustomer = actor.customerId().equals(seller.getOwnerCustomerId());
        if (!sameUser && !sameCustomer) {
            return;
        }
        securityAuditService.recordDenied(
                "SELF_TRADE_" + action,
                "SALE_EVENT",
                event.getSaleEventId(),
                "Seller owner attempted to transact with the owned sale event."
        );
        throw new MarketplaceCheckoutException(
                HttpStatus.FORBIDDEN,
                "SELF_TRADE_FORBIDDEN",
                "본인이 등록한 상품에는 구매, 입찰 또는 래플 응모를 할 수 없습니다."
        );
    }

    public void requireDirectPurchaseCapacity(SaleEvent event, CommerceActor actor, int requestedQuantity) {
        int limit = event.getMaxQuantityPerCustomer() != null
                ? event.getMaxQuantityPerCustomer()
                : Math.max(1, event.getStockQuantity());
        long completed = marketplaceOrderRepository.sumCompletedQuantityForCustomer(
                event.getSaleEventId(), actor.customerId(), COUNTED_ORDER_STATUSES);
        long active = tossPaymentIntentRepository.sumActiveMarketplaceQuantityForCustomer(
                event.getSaleEventId(), actor.customerId(), ACTIVE_INTENT_STATUSES, LocalDateTime.now());
        if (completed + active + requestedQuantity > limit) {
            throw new MarketplaceCheckoutException(
                    HttpStatus.CONFLICT,
                    "PURCHASE_LIMIT_EXCEEDED",
                    "고객별 구매 가능 수량을 초과했습니다."
            );
        }
        if (tossPaymentIntentRepository.countBySaleEventIdAndCustomerIdAndStatusInAndExpiresAtAfter(
                event.getSaleEventId(), actor.customerId(), ACTIVE_INTENT_STATUSES, LocalDateTime.now()) > 0) {
            throw new MarketplaceCheckoutException(
                    HttpStatus.CONFLICT,
                    "CHECKOUT_ALREADY_IN_PROGRESS",
                    "이 판매 이벤트에서 진행 중인 결제창이 있습니다."
            );
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
