package com.example.payment.presentation.controller;

import com.example.payment.application.service.LocalAuthenticationService;
import com.example.payment.presentation.dto.request.LoginRequest;
import com.example.payment.presentation.dto.request.SignUpRequest;
import com.example.payment.presentation.dto.response.AuthTokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자체 계정 회원가입과 로그인.
 *
 * 외부 IdP를 붙이면 이 컨트롤러는 비활성화된다. 그때는 가입과 인증이
 * IdP 쪽 책임이 되고, 이 애플리케이션은 토큰 검증만 한다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.security.local-auth.enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {

    private final LocalAuthenticationService authenticationService;

    @PostMapping("/signup")
    public ResponseEntity<AuthTokenResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(authenticationService.signUp(
                        request.getEmail(), request.getPassword(), request.getDisplayName())));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(
                toResponse(authenticationService.login(request.getEmail(), request.getPassword())));
    }

    private AuthTokenResponse toResponse(LocalAuthenticationService.AuthResult result) {
        return AuthTokenResponse.builder()
                .accessToken(result.accessToken())
                .tokenType("Bearer")
                .expiresIn(result.expiresInSeconds())
                .userId(result.userId())
                .customerId(result.customerId())
                .email(result.email())
                .displayName(result.displayName())
                .roles(result.roles())
                .build();
    }
}
