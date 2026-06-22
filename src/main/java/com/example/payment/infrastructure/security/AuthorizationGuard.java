package com.example.payment.infrastructure.security;

import com.example.payment.domain.entity.InventoryReservationRecord;
import com.example.payment.domain.entity.PaymentRecord;
import com.example.payment.domain.entity.TossPaymentIntent;
import com.example.payment.domain.repository.InventoryReservationRecordRepository;
import com.example.payment.domain.repository.PaymentRecordRepository;
import com.example.payment.domain.repository.TossPaymentIntentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthorizationGuard {

    private final PaymentRecordRepository paymentRecordRepository;
    private final InventoryReservationRecordRepository reservationRepository;
    private final TossPaymentIntentRepository tossPaymentIntentRepository;

    public void requireAuthenticated() {
        if (!isAuthenticated(authentication())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
    }

    public void requireAdmin() {
        requireAuthenticated();
        if (!isAdmin(authentication())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role is required.");
        }
    }

    public void requireCustomerAccess(String customerId) {
        requireAuthenticated();
        Authentication authentication = authentication();
        if (isAdmin(authentication)) {
            return;
        }
        String authenticatedCustomerId = customerId(authentication);
        if (authenticatedCustomerId == null || !authenticatedCustomerId.equals(customerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer ownership mismatch.");
        }
    }

    public void requireSellerAccess(String sellerId) {
        requireAuthenticated();
        Authentication authentication = authentication();
        if (isAdmin(authentication)) {
            return;
        }
        String authenticatedSellerId = sellerId(authentication);
        if (authenticatedSellerId == null || !authenticatedSellerId.equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seller ownership mismatch.");
        }
    }

    public void requirePaymentAccess(String paymentId) {
        requireAuthenticated();
        Authentication authentication = authentication();
        if (isAdmin(authentication)) {
            return;
        }
        PaymentRecord payment = paymentRecordRepository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found."));
        requireCustomerAccess(payment.getCustomerId());
    }

    public void requireReservationAccess(String reservationId) {
        requireAuthenticated();
        Authentication authentication = authentication();
        if (isAdmin(authentication)) {
            return;
        }
        InventoryReservationRecord reservation = reservationRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found."));
        requireCustomerAccess(reservation.getCustomerId());
    }

    public void requireWorkflowAccess(String workflowId) {
        requireAuthenticated();
        Authentication authentication = authentication();
        if (isAdmin(authentication)) {
            return;
        }
        TossPaymentIntent intent = tossPaymentIntentRepository.findByWorkflowId(workflowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found."));
        requireCustomerAccess(intent.getCustomerId());
    }

    public void requireTossIntentAccess(String intentId) {
        requireAuthenticated();
        Authentication authentication = authentication();
        if (isAdmin(authentication)) {
            return;
        }
        TossPaymentIntent intent = tossPaymentIntentRepository.findById(intentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Toss payment intent not found."));
        requireCustomerAccess(intent.getCustomerId());
    }

    private Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private boolean isAdmin(Authentication authentication) {
        return isAuthenticated(authentication) && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority) || "SCOPE_admin".equals(authority));
    }

    private String customerId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof EverySalePrincipal principal) {
            return principal.customerId();
        }
        Object claim = detailsClaim(authentication, "customerId");
        return claim != null ? claim.toString() : authentication.getName();
    }

    private String sellerId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof EverySalePrincipal principal) {
            return principal.sellerId();
        }
        Object claim = detailsClaim(authentication, "sellerId");
        return claim != null ? claim.toString() : null;
    }

    private Object detailsClaim(Authentication authentication, String name) {
        if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
            return jwt.getClaims().get(name);
        }
        return null;
    }
}
