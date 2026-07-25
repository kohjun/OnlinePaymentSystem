package com.example.payment.application.service;

import com.example.payment.domain.model.account.ShippingAddress;
import com.example.payment.domain.model.account.ShippingAddressStatus;
import com.example.payment.domain.repository.ShippingAddressRepository;
import com.example.payment.infrastructure.security.EverySalePrincipal;
import com.example.payment.presentation.dto.request.CompleteReservationRequest;
import com.example.payment.presentation.dto.request.CreateShippingAddressRequest;
import com.example.payment.presentation.dto.response.ShippingAddressResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShippingAddressServiceTest {

    private final ShippingAddressRepository repository = mock(ShippingAddressRepository.class);
    private final ShippingAddressService service = new ShippingAddressService(repository);

    @Test
    void createsFirstAddressAsDefault() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("CUSTOMER"));
        when(repository.findByUserIdAndStatusOrderByDefaultAddressDescCreatedAtDesc("USER-1", ShippingAddressStatus.ACTIVE))
                .thenReturn(List.of());
        when(repository.save(any(ShippingAddress.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShippingAddressResponse response = service.createAddress(principal, request());

        assertEquals("USER-1", response.getUserId());
        assertEquals("CUST-1", response.getCustomerId());
        assertEquals("홍길동", response.getRecipientName());
        assertEquals(Boolean.TRUE, response.getDefaultAddress());
    }

    @Test
    void settingDefaultUnsetsExistingDefaultAddress() {
        EverySalePrincipal principal = new EverySalePrincipal("USER-1", "CUST-1", null, Set.of("CUSTOMER"));
        ShippingAddress existing = address("ADDR-OLD", true);
        ShippingAddress target = address("ADDR-NEW", false);
        when(repository.findByAddressIdAndCustomerIdAndStatus("ADDR-NEW", "CUST-1", ShippingAddressStatus.ACTIVE))
                .thenReturn(Optional.of(target));
        when(repository.findByUserIdAndStatusOrderByDefaultAddressDescCreatedAtDesc("USER-1", ShippingAddressStatus.ACTIVE))
                .thenReturn(List.of(existing, target));
        when(repository.save(any(ShippingAddress.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShippingAddressResponse response = service.setDefaultAddress(principal, "ADDR-NEW");

        assertEquals(Boolean.TRUE, response.getDefaultAddress());
        assertEquals(Boolean.FALSE, existing.getDefaultAddress());
    }

    @Test
    void resolvesOwnedAddressIntoShippingInfoSnapshot() {
        when(repository.findByAddressIdAndCustomerIdAndStatus("ADDR-1", "CUST-1", ShippingAddressStatus.ACTIVE))
                .thenReturn(Optional.of(address("ADDR-1", true)));

        CompleteReservationRequest.ShippingInfo info = service.resolveShippingInfo(
                "CUST-1",
                CompleteReservationRequest.ShippingInfo.builder()
                        .addressId("ADDR-1")
                        .method("PARCEL")
                        .specialInstructions("문 앞")
                        .build()
        );

        assertEquals("ADDR-1", info.getAddressId());
        assertEquals("홍길동", info.getRecipientName());
        assertEquals("04524", info.getPostalCode());
        assertEquals("서울 중구 세종대로 110 10층", info.getAddress());
        assertEquals("010-0000-0000", info.getContactPhone());
    }

    @Test
    void rejectsAddressOwnedByAnotherCustomer() {
        when(repository.findByAddressIdAndCustomerIdAndStatus("ADDR-1", "CUST-2", ShippingAddressStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.resolveShippingInfo(
                "CUST-2",
                CompleteReservationRequest.ShippingInfo.builder().addressId("ADDR-1").build()
        ));
    }

    private CreateShippingAddressRequest request() {
        CreateShippingAddressRequest request = new CreateShippingAddressRequest();
        request.setRecipientName("홍길동");
        request.setContactPhone("010-0000-0000");
        request.setPostalCode("04524");
        request.setAddressLine1("서울 중구 세종대로 110");
        request.setAddressLine2("10층");
        request.setDeliveryMemo("경비실");
        return request;
    }

    private ShippingAddress address(String addressId, boolean defaultAddress) {
        return ShippingAddress.builder()
                .addressId(addressId)
                .userId("USER-1")
                .customerId("CUST-1")
                .recipientName("홍길동")
                .contactPhone("010-0000-0000")
                .postalCode("04524")
                .addressLine1("서울 중구 세종대로 110")
                .addressLine2("10층")
                .deliveryMemo("경비실")
                .defaultAddress(defaultAddress)
                .status(ShippingAddressStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
