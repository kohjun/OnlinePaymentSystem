package com.example.payment.infrastructure.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 추적 ID가 로그 MDC로 전파되는지 확인한다.
 *
 * 로그 패턴에 [trace:%X{traceId}]를 넣어둔 것이 실제로 값을 갖는지는
 * 설정만 봐서는 알 수 없다. MDC 전파가 끊기면 패턴은 그대로 남고 값만
 * 조용히 n/a가 되므로, 장애 조사 때 로그와 추적을 잇지 못하는 것을
 * 그 시점에야 알게 된다.
 *
 * 다른 테스트는 추적을 끄고 돌리므로 여기서만 켠다.
 */
@SpringBootTest(properties = {
        "management.tracing.enabled=true",
        "management.tracing.sampling.probability=1.0",
        // 수집기로 실제로 내보내지 않는다. 여기서 보는 것은 MDC 전파다.
        "management.otlp.tracing.export.enabled=false"
})
class TracingLogCorrelationTest {

    @Autowired
    private Tracer tracer;

    @Test
    @DisplayName("스팬 범위 안에서는 traceId와 spanId가 MDC에 들어온다")
    void traceIdentifiersReachLoggingContext() {
        assertNull(MDC.get("traceId"), "스팬 밖에서는 추적 ID가 없어야 합니다");

        Span span = tracer.nextSpan().name("log-correlation-check").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            String traceId = MDC.get("traceId");
            String spanId = MDC.get("spanId");

            assertNotNull(traceId, "로그 패턴이 참조하는 traceId가 MDC에 있어야 합니다");
            assertNotNull(spanId, "로그 패턴이 참조하는 spanId가 MDC에 있어야 합니다");
            assertEquals(span.context().traceId(), traceId);
            assertEquals(span.context().spanId(), spanId);
        } finally {
            span.end();
        }

        assertNull(MDC.get("traceId"), "스팬을 벗어나면 추적 ID가 남아 있으면 안 됩니다");
    }
}
