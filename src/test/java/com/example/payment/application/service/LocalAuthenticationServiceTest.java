package com.example.payment.application.service;

import com.example.payment.domain.model.account.BuyerProfile;
import com.example.payment.domain.model.account.UserAccount;
import com.example.payment.domain.model.account.UserAccountStatus;
import com.example.payment.domain.repository.BuyerProfileRepository;
import com.example.payment.domain.repository.UserAccountRepository;
import com.example.payment.infrastructure.security.LocalAuthTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAuthenticationServiceTest {

    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final BuyerProfileRepository buyerProfileRepository = mock(BuyerProfileRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final LocalAuthTokenService tokenService = mock(LocalAuthTokenService.class);

    private final LocalAuthenticationService service = new LocalAuthenticationService(
            userAccountRepository, buyerProfileRepository, passwordEncoder, tokenService);

    private void stubTokenIssue() {
        when(tokenService.issue(any(), any())).thenReturn(
                new LocalAuthTokenService.IssuedToken("token-value", Instant.now().plusSeconds(600), 600));
    }

    private UserAccount storedAccount(String email, String rawPassword, UserAccountStatus status) {
        return UserAccount.builder()
                .userId("USER-1")
                .customerId("CUST-1")
                .email(email)
                .passwordHash(rawPassword == null ? null : passwordEncoder.encode(rawPassword))
                .displayName("테스터")
                .status(status)
                .build();
    }

    @Test
    @DisplayName("가입하면 계정과 구매자 프로필이 함께 만들어지고 비밀번호는 해시로만 남는다")
    void signUpCreatesAccountAndBuyerProfile() {
        stubTokenIssue();
        when(userAccountRepository.existsByEmail("buyer@everysale.dev")).thenReturn(false);
        when(userAccountRepository.save(any(UserAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocalAuthenticationService.AuthResult result =
                service.signUp("  Buyer@EverySale.dev ", "P@ssw0rd123", "테스터");

        assertEquals("buyer@everysale.dev", result.email(), "이메일은 소문자로 정규화되어야 합니다");
        assertNotNull(result.customerId());
        assertEquals(List.of("CUSTOMER"), result.roles());

        // 구매자 프로필이 없으면 주문·배송지 흐름이 계정을 찾지 못한다.
        verify(buyerProfileRepository).save(any(BuyerProfile.class));

        org.mockito.ArgumentCaptor<UserAccount> saved =
                org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(saved.capture());
        assertNotEquals("P@ssw0rd123", saved.getValue().getPasswordHash(), "평문이 저장되면 안 됩니다");
        assertTrue(passwordEncoder.matches("P@ssw0rd123", saved.getValue().getPasswordHash()));
    }

    @Test
    @DisplayName("이미 가입된 이메일은 409로 거절한다")
    void duplicateEmailIsRejected() {
        when(userAccountRepository.existsByEmail("buyer@everysale.dev")).thenReturn(true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.signUp("buyer@everysale.dev", "P@ssw0rd123", null));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(userAccountRepository, never()).save(any());
    }

    @Test
    @DisplayName("올바른 자격증명이면 토큰을 발급한다")
    void loginIssuesToken() {
        stubTokenIssue();
        when(userAccountRepository.findByEmail("buyer@everysale.dev"))
                .thenReturn(Optional.of(storedAccount("buyer@everysale.dev", "P@ssw0rd123", UserAccountStatus.ACTIVE)));

        LocalAuthenticationService.AuthResult result = service.login("Buyer@EverySale.dev", "P@ssw0rd123");

        assertEquals("token-value", result.accessToken());
        assertEquals("CUST-1", result.customerId());
    }

    @Test
    @DisplayName("이메일 미존재와 비밀번호 불일치는 같은 응답으로 묶는다")
    void unknownEmailAndWrongPasswordAreIndistinguishable() {
        when(userAccountRepository.findByEmail("missing@everysale.dev")).thenReturn(Optional.empty());
        when(userAccountRepository.findByEmail("buyer@everysale.dev"))
                .thenReturn(Optional.of(storedAccount("buyer@everysale.dev", "P@ssw0rd123", UserAccountStatus.ACTIVE)));

        ResponseStatusException unknown = assertThrows(ResponseStatusException.class,
                () -> service.login("missing@everysale.dev", "P@ssw0rd123"));
        ResponseStatusException wrongPassword = assertThrows(ResponseStatusException.class,
                () -> service.login("buyer@everysale.dev", "wrong-password"));

        // 구분해서 알려주면 어떤 이메일이 가입돼 있는지 확인하는 통로가 된다.
        assertEquals(HttpStatus.UNAUTHORIZED, unknown.getStatusCode());
        assertEquals(unknown.getStatusCode(), wrongPassword.getStatusCode());
        assertEquals(unknown.getReason(), wrongPassword.getReason());
    }

    @Test
    @DisplayName("외부 인증으로 만들어진 계정은 자체 로그인이 되지 않는다")
    void externallyProvisionedAccountCannotLoginLocally() {
        when(userAccountRepository.findByEmail("sso@everysale.dev"))
                .thenReturn(Optional.of(storedAccount("sso@everysale.dev", null, UserAccountStatus.ACTIVE)));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.login("sso@everysale.dev", "anything"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    @DisplayName("정지된 계정은 로그인할 수 없다")
    void suspendedAccountIsRejected() {
        when(userAccountRepository.findByEmail("banned@everysale.dev"))
                .thenReturn(Optional.of(storedAccount("banned@everysale.dev", "P@ssw0rd123", UserAccountStatus.SUSPENDED)));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.login("banned@everysale.dev", "P@ssw0rd123"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }
}
