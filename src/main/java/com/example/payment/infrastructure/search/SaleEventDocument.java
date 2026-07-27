package com.example.payment.infrastructure.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * 검색 색인에 들어가는 판매 이벤트.
 *
 * 검색어를 맞춰볼 텍스트만 담는다. 가격·재고·상태처럼 자주 바뀌고 정확해야
 * 하는 값은 넣지 않는다. 색인은 언제나 조금 뒤처지므로, 그런 값을 여기서
 * 읽으면 품절된 상품을 판매 중으로 보여주게 된다. 검색은 후보 식별자만
 * 고르고 나머지는 DB가 채운다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "everysale-sale-events")
public class SaleEventDocument {

    @Id
    private String saleEventId;

    @Field(type = FieldType.Text)
    private String title;

    @Field(type = FieldType.Text)
    private String description;

    @Field(type = FieldType.Text)
    private String sellerName;

    @Field(type = FieldType.Keyword)
    private String brand;

    @Field(type = FieldType.Text)
    private String tags;
}
