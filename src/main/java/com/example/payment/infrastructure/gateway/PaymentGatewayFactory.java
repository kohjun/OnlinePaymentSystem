package com.example.payment.infrastructure.gateway;

import com.example.payment.domain.service.PaymentGatewayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 결제 게이트웨이 팩토리 - 생성자 문제 해결
 * - 단일 생성자로 Spring 자동 주입 지원
 * - 전략 패턴으로 게이트웨이 선택
 */
@Component
@Slf4j
public class PaymentGatewayFactory {

    private final Map<String, PaymentGatewayService> gatewayMap;
    private final String defaultGateway;

    /**
     * 단일 생성자 - Spring이 자동으로 List<PaymentGatewayService> 주입
     */
    public PaymentGatewayFactory(
            List<PaymentGatewayService> gateways,
            @Value("${payment.default-gateway:MOCK_PAYMENT_GATEWAY}") String defaultGateway) {

        this.defaultGateway = defaultGateway;
        this.gatewayMap = gateways.stream()
                .collect(Collectors.toMap(
                        PaymentGatewayService::getGatewayName,
                        Function.identity()
                ));

        log.info("Initialized payment gateways: {}", gatewayMap.keySet());
        log.info("Default gateway set to: {}", defaultGateway);

        // 기본 게이트웨이 존재 여부 확인
        if (!gatewayMap.containsKey(defaultGateway) && !gatewayMap.isEmpty()) {
            String firstAvailable = gatewayMap.keySet().iterator().next();
            log.warn("Default gateway '{}' not found, using first available: '{}'",
                    defaultGateway, firstAvailable);
        }
    }

    /**
     * 결제 수단에 따른 게이트웨이 선택
     */
    public PaymentGatewayService getGateway(String paymentMethod) {
        log.debug("Selecting gateway for payment method: {}", paymentMethod);

        String gatewayName = selectGatewayByMethod(paymentMethod);
        PaymentGatewayService gateway = gatewayMap.get(gatewayName);

        if (gateway == null) {
            log.warn("Gateway '{}' not found for method '{}', trying default", gatewayName, paymentMethod);
            gateway = gatewayMap.get(defaultGateway);
        }

        if (gateway == null && !gatewayMap.isEmpty()) {
            // 기본 게이트웨이도 없으면 첫 번째 사용 가능한 게이트웨이 사용
            gateway = gatewayMap.values().iterator().next();
            log.warn("Default gateway not available, using fallback: {}", gateway.getGatewayName());
        }

        if (gateway == null) {
            throw new IllegalStateException(
                    String.format("No payment gateway available for method: %s. Available gateways: %s",
                            paymentMethod, gatewayMap.keySet()));
        }

        log.debug("Selected gateway: {} for payment method: {}", gateway.getGatewayName(), paymentMethod);
        return gateway;
    }

    /**
     * 결제 수단별 게이트웨이 매핑 로직
     */
    private String selectGatewayByMethod(String paymentMethod) {
        if (paymentMethod == null) {
            return defaultGateway;
        }

        switch (paymentMethod.toUpperCase().trim()) {
            case "CREDIT_CARD":
            case "DEBIT_CARD":
                return "TOSS_PAYMENTS";

            case "TOSS_PAY":
                return "TOSS_PAYMENTS";

            case "KAKAO_PAY":
                return "KAKAO_GATEWAY";

            case "NAVER_PAY":
                return "NAVER_GATEWAY";

            case "BANK_TRANSFER":
                return "TOSS_PAYMENTS"; // 토스에서 계좌이체도 지원

            case "MOBILE_PAY":
                return "TOSS_PAYMENTS";

            case "MOCK":
            case "TEST":
                return "MOCK_PAYMENT_GATEWAY";

            default:
                log.debug("Unknown payment method '{}', using default gateway", paymentMethod);
                return defaultGateway;
        }
    }

    /**
     * 모든 게이트웨이 헬스체크
     */
    public boolean areAllGatewaysHealthy() {
        if (gatewayMap.isEmpty()) {
            log.warn("No payment gateways configured");
            return false;
        }

        boolean allHealthy = true;
        int healthyCount = 0;
        int totalCount = gatewayMap.size();

        for (Map.Entry<String, PaymentGatewayService> entry : gatewayMap.entrySet()) {
            try {
                boolean healthy = entry.getValue().isHealthy();
                if (healthy) {
                    healthyCount++;
                    log.debug("Gateway {} is healthy", entry.getKey());
                } else {
                    log.warn("Gateway {} is unhealthy", entry.getKey());
                    allHealthy = false;
                }
            } catch (Exception e) {
                log.error("Health check failed for gateway: {}", entry.getKey(), e);
                allHealthy = false;
            }
        }

        log.info("Gateway health check: {}/{} gateways healthy", healthyCount, totalCount);
        return allHealthy;
    }

    /**
     * 개별 게이트웨이 헬스체크 결과 맵 반환
     */
    public Map<String, Boolean> checkAllGatewayHealth() {
        return gatewayMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            try {
                                boolean healthy = entry.getValue().isHealthy();
                                log.debug("Health check for {}: {}", entry.getKey(), healthy);
                                return healthy;
                            } catch (Exception e) {
                                log.error("Health check failed for gateway: {}", entry.getKey(), e);
                                return false;
                            }
                        }
                ));
    }

    /**
     * 사용 가능한 게이트웨이 목록 반환
     */
    public List<String> getAvailableGateways() {
        return gatewayMap.keySet().stream()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 특정 게이트웨이 존재 여부 확인
     */
    public boolean hasGateway(String gatewayName) {
        return gatewayMap.containsKey(gatewayName);
    }

    /**
     * 기본 게이트웨이 이름 반환
     */
    public String getDefaultGatewayName() {
        return defaultGateway;
    }

    /**
     * 게이트웨이 개수 반환
     */
    public int getGatewayCount() {
        return gatewayMap.size();
    }

    /**
     * 팩토리 상태 정보 반환 (모니터링용)
     */
    public Map<String, Object> getFactoryStatus() {
        Map<String, Boolean> healthStatus = checkAllGatewayHealth();
        long healthyCount = healthStatus.values().stream()
                .mapToLong(healthy -> healthy ? 1 : 0)
                .sum();

        return Map.of(
                "totalGateways", gatewayMap.size(),
                "healthyGateways", healthyCount,
                "defaultGateway", defaultGateway,
                "availableGateways", getAvailableGateways(),
                "healthStatus", healthStatus,
                "allHealthy", healthyCount == gatewayMap.size()
        );
    }
}