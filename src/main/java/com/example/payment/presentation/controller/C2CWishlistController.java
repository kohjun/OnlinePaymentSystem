package com.example.payment.presentation.controller;

import com.example.payment.application.service.WishlistService;
import com.example.payment.infrastructure.security.AuthorizationGuard;
import com.example.payment.presentation.dto.response.WishlistItemResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 내 찜 목록.
 *
 * 고객 식별자를 경로나 본문으로 받지 않는다. 인증된 신원에서만 가져오므로
 * 남의 찜 목록에 접근할 경로 자체가 없다.
 */
@RestController
@RequestMapping("/api/c2c/wishlist")
@RequiredArgsConstructor
@Slf4j
public class C2CWishlistController {

    private final AuthorizationGuard authorizationGuard;
    private final WishlistService wishlistService;

    @GetMapping
    public ResponseEntity<List<WishlistItemResponse>> getMyWishlist() {
        return ResponseEntity.ok(wishlistService.list(authorizationGuard.currentCustomerId()));
    }

    @PostMapping("/{saleEventId}")
    public ResponseEntity<WishlistItemResponse> add(@PathVariable String saleEventId) {
        return ResponseEntity.ok(
                wishlistService.add(authorizationGuard.currentCustomerId(), saleEventId));
    }

    @DeleteMapping("/{saleEventId}")
    public ResponseEntity<Void> remove(@PathVariable String saleEventId) {
        wishlistService.remove(authorizationGuard.currentCustomerId(), saleEventId);
        // 담겨 있지 않았어도 결과적으로 원하는 상태이므로 204로 통일한다.
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
