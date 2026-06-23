package com.example.payment.presentation.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@ConditionalOnProperty(name = "app.simulation.auth.enabled", havingValue = "true")
@RequestMapping("/api/simulation/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.simulation.auth.username:}")
    private String demoUsername;

    @Value("${app.simulation.auth.password:}")
    private String demoPassword;

    private static final String LOGIN_KEY = "auth:is_logged_in";
    private static final String USERNAME_KEY = "auth:username";
    private static final String SUBSCRIBED_KEY = "auth:is_subscribed";

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Object loggedIn = redisTemplate.opsForValue().get(LOGIN_KEY);
        Object username = redisTemplate.opsForValue().get(USERNAME_KEY);
        Object subscribed = redisTemplate.opsForValue().get(SUBSCRIBED_KEY);

        boolean isLoggedIn = loggedIn != null && "true".equalsIgnoreCase(loggedIn.toString());
        boolean isSubscribed = subscribed != null && "true".equalsIgnoreCase(subscribed.toString());

        return ResponseEntity.ok(Map.of(
                "isLoggedIn", isLoggedIn,
                "username", (username != null) ? username.toString() : "",
                "isSubscribed", isSubscribed
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestParam String username, @RequestParam String password) {
        if (hasText(demoUsername) && hasText(demoPassword)
                && demoUsername.equals(username)
                && demoPassword.equals(password)) {
            redisTemplate.opsForValue().set(LOGIN_KEY, "true");
            redisTemplate.opsForValue().set(USERNAME_KEY, username);
            log.info("Merchant login successful: {}", username);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "로그인 성공"));
        } else {
            return ResponseEntity.status(401).body(Map.of("status", "FAILED", "message", "아이디 또는 비밀번호가 올바르지 않습니다."));
        }
    }

    @PostMapping("/subscribe")
    public ResponseEntity<Map<String, Object>> subscribe() {
        redisTemplate.opsForValue().set(SUBSCRIBED_KEY, "true");
        log.info("Merchant subscription payment completed successfully.");
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "구독 결제가 완료되었습니다."));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        redisTemplate.delete(LOGIN_KEY);
        redisTemplate.delete(USERNAME_KEY);
        redisTemplate.delete(SUBSCRIBED_KEY);
        log.info("Merchant logged out and subscription reset.");
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "로그아웃 완료"));
    }
}
