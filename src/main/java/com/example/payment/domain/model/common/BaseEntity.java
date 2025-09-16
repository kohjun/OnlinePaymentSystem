package com.example.payment.domain.model.common;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 공통 엔티티 기본 클래스
 * - 생성/수정 시간 관리
 * - 감사 정보 (생성자/수정자) 관리
 */
@Data
public abstract class BaseEntity {
    protected LocalDateTime createdAt;
    protected LocalDateTime updatedAt;
    protected String createdBy;
    protected String updatedBy;

    /**
     * 생성 시 호출
     */
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    /**
     * 수정 시 호출
     */
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 생성자와 함께 생성 시간 설정
     */
    public void onCreate(String createdBy) {
        this.createdBy = createdBy;
        onCreate();
    }

    /**
     * 수정자와 함께 수정 시간 설정
     */
    public void onUpdate(String updatedBy) {
        this.updatedBy = updatedBy;
        onUpdate();
    }
}