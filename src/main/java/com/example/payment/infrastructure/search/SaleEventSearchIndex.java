package com.example.payment.infrastructure.search;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 판매 이벤트 검색 색인 접근.
 *
 * 색인 장애가 마켓플레이스를 멈추게 해서는 안 된다. 모든 메서드는 실패를
 * 삼키고 비어 있는 결과나 false를 돌려주며, 호출부는 그때 DB 경로로
 * 되돌아간다. 검색이 조금 나빠지는 것과 상품 목록이 아예 안 뜨는 것은
 * 전혀 다른 문제다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class SaleEventSearchIndex {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final SaleEventSearchProperties properties;

    /** 색인 전체를 주어진 문서로 맞춘다. 사라진 이벤트는 색인에서도 지운다. */
    public boolean replaceAll(List<SaleEventDocument> documents) {
        try {
            elasticsearchTemplate.indexOps(SaleEventDocument.class).createWithMapping();

            if (!documents.isEmpty()) {
                List<IndexQuery> queries = documents.stream()
                        .map(document -> (IndexQuery) new IndexQueryBuilder()
                                .withId(document.getSaleEventId())
                                .withObject(document)
                                .build())
                        .toList();
                elasticsearchTemplate.bulkIndex(queries, SaleEventDocument.class);
            }

            elasticsearchTemplate.indexOps(SaleEventDocument.class).refresh();
            removeStale(documents.stream().map(SaleEventDocument::getSaleEventId).toList());
            return true;
        } catch (RuntimeException e) {
            log.warn("Search reindex failed; keyword search stays on the database path: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 키워드에 맞는 판매 이벤트 식별자를 관련도 순으로 돌려준다.
     *
     * 결과가 비어 있는 것과 색인을 쓸 수 없는 것은 다르다. 전자는 정말로
     * 맞는 상품이 없다는 뜻이고, 후자는 DB로 되돌아가야 한다는 뜻이다.
     * 그래서 Optional로 구분한다.
     */
    public Optional<List<String>> findEventIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Optional.empty();
        }
        try {
            Query query = Query.of(builder -> builder
                    .multiMatch(match -> match
                            .query(keyword.trim())
                            .fields("title^3", "brand^2", "sellerName", "tags", "description")
                            .fuzziness("AUTO")));

            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(query)
                    .withMaxResults(properties.getMaxCandidates())
                    .build();

            SearchHits<SaleEventDocument> hits =
                    elasticsearchTemplate.search(nativeQuery, SaleEventDocument.class);

            return Optional.of(hits.getSearchHits().stream()
                    .map(hit -> hit.getContent().getSaleEventId())
                    .toList());
        } catch (RuntimeException e) {
            log.warn("Search query failed; falling back to the database path: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void removeStale(List<String> keepIds) {
        try {
            NativeQuery all = NativeQuery.builder()
                    .withQuery(Query.of(builder -> builder.matchAll(match -> match)))
                    .withMaxResults(10_000)
                    .build();
            elasticsearchTemplate.search(all, SaleEventDocument.class).getSearchHits().stream()
                    .map(hit -> hit.getContent().getSaleEventId())
                    .filter(id -> !keepIds.contains(id))
                    .forEach(id -> elasticsearchTemplate.delete(id, SaleEventDocument.class));
        } catch (RuntimeException e) {
            log.warn("Could not prune stale search documents: {}", e.getMessage());
        }
    }
}
