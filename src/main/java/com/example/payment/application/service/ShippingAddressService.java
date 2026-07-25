package com.example.payment.application.service;

import com.example.payment.domain.model.account.ShippingAddress;
import com.example.payment.domain.model.account.ShippingAddressStatus;
import com.example.payment.domain.repository.ShippingAddressRepository;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.CreateShippingAddressRequest;
import com.example.payment.presentation.dto.response.ShippingAddressResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShippingAddressService {

    private final ShippingAddressRepository shippingAddressRepository;

    @Transactional
    public ShippingAddressResponse createAddress(EverySalePrincipal principal, CreateShippingAddressRequest request) {
        String userId = requireText(principal.userId(), "Authenticated user id is required.");
        String customerId = requireText(principal.customerId(), "Authenticated customer id is required.");
        boolean makeDefault = Boolean.TRUE.equals(request.getDefaultAddress())
                || shippingAddressRepository.findByUserIdAndStatusOrderByDefaultAddressDescCreatedAtDesc(
                        userId,
                        ShippingAddressStatus.ACTIVE
                ).isEmpty();

        if (makeDefault) {
            unsetDefaultAddresses(userId);
        }

        ShippingAddress address = shippingAddressRepository.save(ShippingAddress.builder()
                .addressId("ADDR-" + shortId())
                .userId(userId)
                .customerId(customerId)
                .label(trimToNull(request.getLabel()))
                .recipientName(requireText(request.getRecipientName(), "recipientName is required."))
                .contactPhone(requireText(request.getContactPhone(), "contactPhone is required."))
                .postalCode(trimToNull(request.getPostalCode()))
                .addressLine1(requireText(request.getAddressLine1(), "addressLine1 is required."))
                .addressLine2(trimToNull(request.getAddressLine2()))
                .deliveryMemo(trimToNull(request.getDeliveryMemo()))
                .defaultAddress(makeDefault)
                .status(ShippingAddressStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build());
        return toResponse(address);
    }

    @Transactional(readOnly = true)
    public List<ShippingAddressResponse> getMyAddresses(EverySalePrincipal principal) {
        String userId = requireText(principal.userId(), "Authenticated user id is required.");
        return shippingAddressRepository.findByUserIdAndStatusOrderByDefaultAddressDescCreatedAtDesc(
                        userId,
                        ShippingAddressStatus.ACTIVE
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ShippingAddressResponse setDefaultAddress(EverySalePrincipal principal, String addressId) {
        ShippingAddress address = requireOwnedActiveAddress(principal, addressId);
        unsetDefaultAddresses(address.getUserId());
        address.setDefaultAddress(true);
        return toResponse(shippingAddressRepository.save(address));
    }

    @Transactional
    public void deleteAddress(EverySalePrincipal principal, String addressId) {
        ShippingAddress address = requireOwnedActiveAddress(principal, addressId);
        address.setDefaultAddress(false);
        address.setStatus(ShippingAddressStatus.DELETED);
        shippingAddressRepository.save(address);
    }

    @Transactional(readOnly = true)
    public CompleteReservationRequest.ShippingInfo resolveShippingInfo(String customerId,
                                                                       CompleteReservationRequest.ShippingInfo shippingInfo) {
        if (shippingInfo == null || !hasText(shippingInfo.getAddressId())) {
            return shippingInfo;
        }
        ShippingAddress address = shippingAddressRepository.findByAddressIdAndCustomerIdAndStatus(
                        shippingInfo.getAddressId(),
                        customerId,
                        ShippingAddressStatus.ACTIVE
                )
                .orElseThrow(() -> new IllegalArgumentException("Shipping address not found for customer: " + shippingInfo.getAddressId()));
        return CompleteReservationRequest.ShippingInfo.builder()
                .addressId(address.getAddressId())
                .recipientName(address.getRecipientName())
                .postalCode(address.getPostalCode())
                .address(fullAddress(address))
                .method(defaultText(shippingInfo.getMethod(), "PARCEL"))
                .specialInstructions(defaultText(shippingInfo.getSpecialInstructions(), address.getDeliveryMemo()))
                .contactPhone(address.getContactPhone())
                .build();
    }

    private void unsetDefaultAddresses(String userId) {
        List<ShippingAddress> addresses = shippingAddressRepository.findByUserIdAndStatusOrderByDefaultAddressDescCreatedAtDesc(
                userId,
                ShippingAddressStatus.ACTIVE
        );
        for (ShippingAddress address : addresses) {
            if (Boolean.TRUE.equals(address.getDefaultAddress())) {
                address.setDefaultAddress(false);
                shippingAddressRepository.save(address);
            }
        }
    }

    private ShippingAddress requireOwnedActiveAddress(EverySalePrincipal principal, String addressId) {
        String customerId = requireText(principal.customerId(), "Authenticated customer id is required.");
        return shippingAddressRepository.findByAddressIdAndCustomerIdAndStatus(
                        addressId,
                        customerId,
                        ShippingAddressStatus.ACTIVE
                )
                .orElseThrow(() -> new IllegalArgumentException("Shipping address not found: " + addressId));
    }

    private ShippingAddressResponse toResponse(ShippingAddress address) {
        return ShippingAddressResponse.builder()
                .addressId(address.getAddressId())
                .userId(address.getUserId())
                .customerId(address.getCustomerId())
                .label(address.getLabel())
                .recipientName(address.getRecipientName())
                .contactPhone(address.getContactPhone())
                .postalCode(address.getPostalCode())
                .addressLine1(address.getAddressLine1())
                .addressLine2(address.getAddressLine2())
                .deliveryMemo(address.getDeliveryMemo())
                .defaultAddress(address.getDefaultAddress())
                .status(address.getStatus())
                .createdAt(address.getCreatedAt())
                .updatedAt(address.getUpdatedAt())
                .build();
    }

    private String fullAddress(ShippingAddress address) {
        if (!hasText(address.getAddressLine2())) {
            return address.getAddressLine1();
        }
        return address.getAddressLine1() + " " + address.getAddressLine2();
    }

    private String requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
