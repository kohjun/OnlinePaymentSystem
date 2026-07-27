package com.example.payment.infrastructure.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.search")
public class SaleEventSearchProperties {

    /**
     * 검색 색인 사용 여부. 꺼도 마켓플레이스는 그대로 동작한다.
     * 키워드 검색이 DB LIKE 질의로 되돌아갈 뿐이다.
     */
    private boolean enabled = false;

    /** 색인에서 가져올 최대 후보 수. 이 안에서 DB가 상세를 채운다. */
    private int maxCandidates = 200;

    /** 주기적 재색인 간격. 판매 이벤트는 자주 바뀌지 않아 짧을 필요가 없다. */
    private long reindexFixedDelayMs = 300_000;

    private long reindexInitialDelayMs = 20_000;
}
