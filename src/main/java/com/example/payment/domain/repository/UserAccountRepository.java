package com.example.payment.domain.repository;

import com.example.payment.domain.model.account.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, String> {
    Optional<UserAccount> findByCustomerId(String customerId);

    /** 로그인 조회. 이메일은 저장 시 소문자로 정규화된다. */
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);
}
