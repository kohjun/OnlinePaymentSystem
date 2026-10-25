package com.example.payment.infrastructure.config;

import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.context.ApplicationListener;

import java.time.ZoneOffset;
import java.util.TimeZone;

/**
 * 애플리케이션 시각 기준을 UTC로 고정한다.
 *
 * 마켓플레이스 쪽은 {@code LocalDateTime.now(ZoneOffset.UTC)}로, 결제·재고 쪽은
 * {@code LocalDateTime.now()}로 시각을 만들어 왔다. 둘 다 시간대 없는
 * {@code TIMESTAMP}에 저장되므로 서버 기본 시간대가 UTC가 아니면 값이 서로
 * 어긋난다. KST 서버에서는 래플·경매 낙찰자 재고 hold의 만료 시각이 9시간
 * 과거로 계산되어 추첨과 마감이 실패한다.
 *
 * 컨텍스트가 만들어지기 전({@link ApplicationStartingEvent})에 고정해야 JDBC
 * 세션 시간대, 스케줄러, 로그까지 같은 기준을 쓴다. spring.factories로
 * 등록하므로 main, SpringBootTest, 테스트 하위 프로세스 모두 같은 경로를 탄다.
 */
public class UtcTimeZoneInitializer implements ApplicationListener<ApplicationStartingEvent> {

    public static final ZoneOffset APPLICATION_ZONE = ZoneOffset.UTC;

    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        pin();
    }

    public static void pin() {
        TimeZone.setDefault(TimeZone.getTimeZone(APPLICATION_ZONE));
    }
}
