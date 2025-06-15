package com.example.payment.presentation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * 웹 MVC 설정
 * - 인터셉터 등록
 * - API 요청 모니터링
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestMonitoringInterceptor requestMonitoringInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // API 모니터링 인터셉터 등록
        registry.addInterceptor(requestMonitoringInterceptor)
                .addPathPatterns("/api/**"); // API 경로만 모니터링
    }
}