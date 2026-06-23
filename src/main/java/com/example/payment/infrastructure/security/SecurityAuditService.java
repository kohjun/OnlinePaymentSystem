package com.example.payment.infrastructure.security;

import com.example.payment.domain.entity.SecurityAuditEvent;
import com.example.payment.domain.repository.SecurityAuditEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityAuditService {

    private final SecurityAuditEventRepository repository;

    @Value("${app.audit.enabled:true}")
    private boolean enabled;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, String outcome, String reason) {
        if (!enabled) {
            return;
        }
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            HttpServletRequest request = currentRequest();
            repository.save(SecurityAuditEvent.builder()
                    .eventId("AUD-" + UUID.randomUUID())
                    .actorId(actorId(authentication))
                    .actorRoles(actorRoles(authentication))
                    .action(limit(action, 100))
                    .resourceType(limit(resourceType, 100))
                    .resourceId(limit(resourceId, 200))
                    .outcome(limit(outcome, 50))
                    .reason(limit(reason, 2000))
                    .ipAddress(limit(clientIp(request), 100))
                    .userAgent(limit(request != null ? request.getHeader("User-Agent") : null, 500))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Failed to write security audit event: action={}, resourceType={}, resourceId={}",
                    action, resourceType, resourceId, e);
        }
    }

    public void recordGranted(String action, String resourceType, String resourceId) {
        record(action, resourceType, resourceId, "GRANTED", null);
    }

    public void recordDenied(String action, String resourceType, String resourceId, String reason) {
        record(action, resourceType, resourceId, "DENIED", reason);
    }

    private String actorId(Authentication authentication) {
        if (authentication == null) {
            return "anonymous";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof EverySalePrincipal everySalePrincipal) {
            return everySalePrincipal.customerId();
        }
        if (principal instanceof Jwt jwt) {
            Object customerId = jwt.getClaims().get("customerId");
            return customerId != null ? customerId.toString() : jwt.getSubject();
        }
        return authentication.getName();
    }

    private String actorRoles(Authentication authentication) {
        if (authentication == null) {
            return "";
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}