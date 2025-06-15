package com.example.payment.presentation.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import lombok.extern.slf4j.Slf4j;

/**
 * 간단한 API 요청 로깅 인터셉터
 * - 요청/응답 기본 정보만 로깅
 */
@Slf4j
@Component
public class RequestMonitoringInterceptor implements HandlerInterceptor {

    // 요청 시작 시간을 저장하기 위한 request attribute 키
    private static final String REQ_START_TIME = "requestStartTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 요청 시작 시간 기록
        request.setAttribute(REQ_START_TIME, System.currentTimeMillis());

        log.debug("요청 시작: {} {}", request.getMethod(), request.getRequestURI());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        // 특별한 처리 없음
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 요청 처리 시간 계산 및 로깅
        Long startTime = (Long) request.getAttribute(REQ_START_TIME);

        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;

            log.debug("요청 완료: {} {} - {}ms (Status: {})",
                    request.getMethod(),
                    request.getRequestURI(),
                    duration,
                    response.getStatus());

            // 느린 요청만 경고 로그
            if (duration > 1000) { // 1초 이상
                log.warn("느린 요청 감지: {} {} - {}ms",
                        request.getMethod(), request.getRequestURI(), duration);
            }
        }
    }
}