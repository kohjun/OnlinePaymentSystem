package com.example.payment.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import com.example.payment.service.MonitoringService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * API 요청 모니터링 인터셉터
 * - API 요청/응답 성능 측정
 * - 모니터링 서비스와 연동
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestMonitoringInterceptor implements HandlerInterceptor {

    private final MonitoringService monitoringService;

    // 요청 시작 시간을 저장하기 위한 request attribute 키
    private static final String REQ_START_TIME = "requestStartTime";
    private static final String REQ_ENDPOINT = "requestEndpoint";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 요청 시작 시간 기록
        request.setAttribute(REQ_START_TIME, System.currentTimeMillis());

        // 엔드포인트 정규화 및 기록
        String endpoint = normalizeEndpoint(request.getRequestURI());
        request.setAttribute(REQ_ENDPOINT, endpoint);

        // 모니터링 서비스에 요청 시작 기록
        monitoringService.recordRequestStart(endpoint);

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        // 이 메서드에서는 특별한 작업 없음
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 요청 정보 가져오기
        Long startTime = (Long) request.getAttribute(REQ_START_TIME);
        String endpoint = (String) request.getAttribute(REQ_ENDPOINT);

        if (startTime != null && endpoint != null) {
            // 요청 처리 시간 계산
            long duration = System.currentTimeMillis() - startTime;

            // 요청 성공 여부 확인
            boolean success = isSuccessResponse(response.getStatus());

            // 모니터링 서비스에 요청 완료 기록
            monitoringService.recordRequestEnd(endpoint, success);

            // 특정 조건에 따라 디버그 로깅 (성능 문제 추적용)
            if (duration > 500) { // 500ms 이상 소요된 요청에 대해 로깅
                log.warn("Slow request: {} {} - {}ms (Status: {})",
                        request.getMethod(), endpoint, duration, response.getStatus());
            }
        }
    }

    /**
     * 엔드포인트 정규화
     * - ID와 같은 가변 부분을 패턴으로 대체하여 동일한 API를 그룹화
     */
    private String normalizeEndpoint(String uri) {
        // 예: /api/payment/12345 → /api/payment/{paymentId}
        String normalized = uri;

        // ID 패턴 부분 추출 정규화
        if (uri.matches("/api/payment/[a-zA-Z0-9-]+")) {
            normalized = "/api/payment/{paymentId}";
        } else if (uri.matches("/api/orders/[a-zA-Z0-9-]+")) {
            normalized = "/api/orders/{orderId}";
        }

        return normalized;
    }

    /**
     * 응답 성공 여부 확인
     */
    private boolean isSuccessResponse(int status) {
        return status >= 200 && status < 400;
    }
}