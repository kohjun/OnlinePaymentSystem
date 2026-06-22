package com.example.payment.application.service;

import com.example.payment.domain.model.inventory.Product;
import com.example.payment.domain.model.marketplace.SaleEvent;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CheckoutPricingService {

    private static final String DEFAULT_CURRENCY = "KRW";

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public PriceSnapshot applyProductPrice(CompleteReservationRequest request, boolean rejectMismatch) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + request.getProductId()));
        return applyPrice(request, product.getPrice(), DEFAULT_CURRENCY, "PRODUCT:" + product.getId(), rejectMismatch);
    }

    public PriceSnapshot applySaleEventPrice(CompleteReservationRequest request, SaleEvent event, boolean rejectMismatch) {
        return applyPrice(request, event.getPrice(), DEFAULT_CURRENCY, "SALE_EVENT:" + event.getSaleEventId(), rejectMismatch);
    }

    private PriceSnapshot applyPrice(CompleteReservationRequest request,
                                     BigDecimal unitPrice,
                                     String currency,
                                     String priceSource,
                                     boolean rejectMismatch) {
        BigDecimal total = money(unitPrice).multiply(BigDecimal.valueOf(request.getQuantity()));
        BigDecimal clientAmount = request.getPaymentInfo().getAmount();
        if (rejectMismatch && clientAmount != null && money(clientAmount).compareTo(total) != 0) {
            throw new AmountMismatchException(
                    "AMOUNT_MISMATCH: expected " + total + " " + currency + " but client sent " + clientAmount
            );
        }

        request.getPaymentInfo().setAmount(total);
        request.getPaymentInfo().setCurrency(currency);
        return PriceSnapshot.builder()
                .unitPrice(money(unitPrice))
                .amount(total)
                .currency(currency)
                .priceSource(priceSource)
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    @Value
    @Builder
    public static class PriceSnapshot {
        BigDecimal unitPrice;
        BigDecimal amount;
        String currency;
        String priceSource;
        LocalDateTime calculatedAt;
    }
}
