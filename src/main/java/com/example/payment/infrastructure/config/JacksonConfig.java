package com.example.payment.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

@Configuration
public class JacksonConfig {

    /**
     * 애플리케이션의 LocalDateTime은 모두 UTC다({@link UtcTimeZoneInitializer}).
     *
     * 오프셋 없이 "2026-10-25T10:00:00"으로 내보내면 브라우저는 그 값을 현지
     * 시각으로 읽는다. 한국 브라우저에서는 마감·시작 시각과 카운트다운이 9시간
     * 앞당겨 보인다. 끝에 Z를 붙여 UTC임을 밝힌다. 응답 DTO의 {@code @JsonFormat}은
     * {@code yyyy-MM-dd'T'HH:mm:ss['Z']}를 쓴다. 대괄호 구간은 출력할 때 Z를 붙이고,
     * 읽을 때는 Z가 없는 예전 저장 본문도 받아들인다.
     */
    static final DateTimeFormatter UTC_LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendLiteral('Z')
            .toFormatter();

    @Bean
    Jackson2ObjectMapperBuilderCustomizer utcLocalDateTimeSerializer() {
        return builder -> builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(UTC_LOCAL_DATE_TIME));
    }

    /**
     * API 응답과 서비스가 쓰는 매퍼.
     *
     * RedisConfig가 Redis 전용 매퍼를 빈으로 등록하면 Spring Boot는 자기 매퍼를
     * 만들지 않는다. 그동안 Redis 매퍼가 MVC 응답까지 맡아 spring.jackson.* 설정과
     * ProblemDetail 믹스인이 빠졌고, 오류 응답의 code가 properties 안에 묻혀
     * 프론트가 읽지 못했다. Boot와 같은 방식으로 빌더에서 만들어 기본 매퍼로 둔다.
     * Redis 쪽은 @Qualifier("redisObjectMapper")로 따로 받는다.
     */
    @Bean
    @Primary
    ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.createXmlMapper(false).build();
    }
}
