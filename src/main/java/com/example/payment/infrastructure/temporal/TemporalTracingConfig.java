package com.example.payment.infrastructure.temporal;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Saga 워크플로와 액티비티를 호출자 추적에 잇는다.
 *
 * 이것이 없으면 결제 추적이 워크플로를 시작하는 순간 끊긴다. 재고 선점,
 * 주문 생성, 결제 승인, 확정이 각각 얼마나 걸렸는지, 어느 단계에서 보상이
 * 돌았는지가 추적에서 보이지 않아 정작 이 시스템에서 가장 궁금한 구간이
 * 비게 된다.
 *
 * Temporal SDK는 계측을 OpenTracing API로 노출하므로 OTel과 직접 붙지
 * 않는다. OpenTracingShim이 그 사이를 잇는다. 스팬 컨텍스트는 워크플로
 * 시작 시 Temporal 헤더에 실려 워커까지 전파되므로, 클라이언트와 워커
 * 양쪽에 인터셉터를 걸어야 한 트레이스로 이어진다.
 */
// @ConditionalOnBean은 쓰지 않는다. 사용자 @Configuration은 자동설정보다 먼저
// 평가되므로, 그 시점에는 OpenTelemetry 빈이 아직 등록되기 전이라 조건이 항상
// 거짓이 된다. 그러면 설정은 조용히 통째로 빠지고 워크플로 스팬만 사라진다.
// 추적이 켜져 있으면 OpenTelemetry 빈은 반드시 존재하므로 프로퍼티로만 건다.
@Configuration
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true", matchIfMissing = true)
public class TemporalTracingConfig {

    @Bean
    public OpenTracingOptions temporalOpenTracingOptions(OpenTelemetry openTelemetry) {
        return OpenTracingOptions.newBuilder()
                .setTracer(OpenTracingShim.createTracerShim(openTelemetry))
                .build();
    }

    /** 워크플로를 시작하는 쪽. 현재 스팬 컨텍스트를 Temporal 헤더에 실어 보낸다. */
    @Bean
    public OpenTracingClientInterceptor temporalTracingClientInterceptor(OpenTracingOptions options) {
        return new OpenTracingClientInterceptor(options);
    }

    /** 워크플로와 액티비티를 실행하는 쪽. 전달받은 컨텍스트 아래로 스팬을 잇는다. */
    @Bean
    public OpenTracingWorkerInterceptor temporalTracingWorkerInterceptor(OpenTracingOptions options) {
        return new OpenTracingWorkerInterceptor(options);
    }
}
