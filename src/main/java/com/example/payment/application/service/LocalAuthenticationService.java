package com.example.payment.application.service;

import com.example.payment.domain.model.account.BuyerProfile;
import com.example.payment.domain.model.account.BuyerStatus;
import com.example.payment.domain.model.account.UserAccount;
import com.example.payment.domain.model.account.UserAccountStatus;
import com.example.payment.domain.repository.BuyerProfileRepository;
import com.example.payment.domain.repository.UserAccountRepository;
import com.example.payment.infrastructure.security.LocalAuthTokenService;
import com.example.payment.infrastructure.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * 자체 계정 회원가입과 로그인.
 *
 * 가입 시 users와 buyer_profiles를 함께 만든다. 구매자 프로필이 없으면
 * 주문·배송지 같은 기존 흐름이 계정을 찾지 못한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalAuthenticationService {

    private static final List<String> DEFAULT_ROLES = List.of("CUSTOMER");

    private final UserAccountRepository userAccountRepository;
    private final BuyerProfileRepository buyerProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final LocalAuthTokenService tokenService;

    @Transactional
    public AuthResult signUp(String email, String rawPassword, String displayName) {
        String normalizedEmail = normalizeEmail(email);

        if (userAccountRepository.existsByEmail(normalizedEmail)) {
            // 어떤 이메일이 가입돼 있는지 알려주는 셈이지만, 가입 화면에서는
            // 사용자가 자기 이메일을 입력한 상황이라 실익이 더 크다.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }

        String userId = "USER-" + IdGenerator.generateEventId();
        String customerId = "CUST-" + IdGenerator.generateEventId();

        UserAccount account = userAccountRepository.save(UserAccount.builder()
                .userId(userId)
                .customerId(customerId)
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .displayName(defaultText(displayName, normalizedEmail))
                .status(UserAccountStatus.ACTIVE)
                .build());

        buyerProfileRepository.save(BuyerProfile.builder()
                .userId(userId)
                .customerId(customerId)
                .displayName(account.getDisplayName())
                .status(BuyerStatus.ACTIVE)
                .build());

        log.info("Local account created: userId={}, customerId={}", userId, customerId);
        return toResult(account);
    }

    @Transactional
    public AuthResult login(String email, String rawPassword) {
        String normalizedEmail = normalizeEmail(email);

        UserAccount account = userAccountRepository.findByEmail(normalizedEmail)
                .orElseThrow(LocalAuthenticationService::invalidCredentials);

        // 외부 인증으로 만들어진 계정에는 비밀번호가 없다. 그 계정으로
        // 자체 로그인을 시도하면 자격증명 오류와 같은 응답을 준다.
        if (account.getPasswordHash() == null
                || !passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (account.getStatus() != UserAccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "사용할 수 없는 계정입니다.");
        }

        return toResult(account);
    }

    private AuthResult toResult(UserAccount account) {
        LocalAuthTokenService.IssuedToken token = tokenService.issue(account, DEFAULT_ROLES);
        return new AuthResult(
                token.accessToken(),
                token.expiresInSeconds(),
                account.getUserId(),
                account.getCustomerId(),
                account.getEmail(),
                account.getDisplayName(),
                DEFAULT_ROLES
        );
    }

    /**
     * 이메일 미존재와 비밀번호 불일치를 같은 응답으로 묶는다.
     * 구분해서 알려주면 어떤 이메일이 가입돼 있는지 확인하는 통로가 된다.
     */
    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일을 입력하세요.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record AuthResult(
            String accessToken,
            long expiresInSeconds,
            String userId,
            String customerId,
            String email,
            String displayName,
            List<String> roles
    ) {
    }
}
