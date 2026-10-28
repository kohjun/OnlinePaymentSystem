package com.example.payment.infrastructure.persistence.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

import com.example.payment.infrastructure.messaging.realtime.RedisMarketplaceRealtimeListener;
import com.example.payment.infrastructure.messaging.realtime.RedisMarketplaceRealtimePublisher;

/**
 * Redis 단일 모드 설정
 * - 클러스터 모드 대신 단일 Redis 인스턴스 사용
 * - [수정] Lua 스크립트 반환 값(순수 JSON)의 역직렬화 오류를 해결하기 위해
 * ObjectMapper의 `activateDefaultTyping` 옵션 제거
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.timeout:5000}")
    private long timeout;

    /**
     * Redis 값·캐시·Pub/Sub 전용 매퍼. 필드 직접 접근(ANY)이 캐시된 도메인 객체의
     * 저장 형식에 맞춰져 있어 API 응답에는 쓰지 않는다. 받는 쪽은
     * {@code @Qualifier("redisObjectMapper")}로 명시하고, 기본 매퍼는 JacksonConfig가 둔다.
     *
     * Jackson 2 시절 저장된 캐시를 그대로 읽고 같은 형식으로 쓰도록 Jackson 2 기본값에서
     * 시작한다. Jackson 3 기본값은 final 필드를 채우지 않고 원시 타입 null을 거부한다.
     */
    @Bean
    public JsonMapper redisObjectMapper() {
        JsonMapper mapper = JsonMapper.builder()
                .configureForJackson2()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .changeDefaultVisibility(visibility -> visibility.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY))
                .build();

        // [수정] 아래 코드가 Lua 스크립트의 순수 JSON 반환 값을 역직렬화할 때
        // MismatchedInputException (START_OBJECT vs START_ARRAY) 오류를 유발하므로 제거합니다.
        // mapper.activateDefaultTyping(
        //         mapper.getPolymorphicTypeValidator(),
        //         ObjectMapper.DefaultTyping.NON_FINAL
        // );
        return mapper;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 단일 Redis 설정
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);

        if (password != null && !password.trim().isEmpty()) {
            redisConfig.setPassword(password);
        }

        // Lettuce 클라이언트 설정
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeout))
                .clientOptions(createClientOptions())
                .build();

        return new LettuceConnectionFactory(redisConfig, clientConfig);
    }

    private io.lettuce.core.ClientOptions createClientOptions() {
        return io.lettuce.core.ClientOptions.builder()
                .disconnectedBehavior(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .socketOptions(io.lettuce.core.SocketOptions.builder()
                        .connectTimeout(Duration.ofMillis(timeout))
                        .keepAlive(true)
                        .tcpNoDelay(true)
                        .build())
                .publishOnScheduler(true)
                .timeoutOptions(io.lettuce.core.TimeoutOptions.builder()
                        .fixedTimeout(Duration.ofMillis(timeout))
                        .build())
                .build();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       @Qualifier("redisObjectMapper") ObjectMapper redisObjectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serialization
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value serialization
        JacksonJsonRedisSerializer<Object> jacksonSerializer = new JacksonJsonRedisSerializer<>(redisObjectMapper, Object.class);
        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);

        // 단일 모드에서는 트랜잭션 지원 가능
        template.setEnableTransactionSupport(false);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnProperty(name = "app.marketplace.realtime.redis-broadcast-enabled", havingValue = "true")
    public RedisMessageListenerContainer marketplaceRealtimeListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisMarketplaceRealtimeListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(RedisMarketplaceRealtimePublisher.CHANNEL));
        return container;
    }

    // ==================== Lua Script Beans 추가 ====================

    /**
     * 재고 선점 스크립트.
     *
     * 세 스크립트 모두 cjson.encode()로 문자열을 돌려주므로 결과 타입은
     * String이다. 호출부에서 인자와 결과 모두 String 직렬화기로 실행해,
     * JSON 직렬화기가 문자열 인자를 따옴표로 감싸는 것을 피한다.
     */
    @Bean
    public DefaultRedisScript<String> reserveScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/reserve_resource.lua"));
        script.setResultType(String.class);
        return script;
    }

    /**
     * 예약 확정 스크립트
     */
    @Bean
    public DefaultRedisScript<String> confirmScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/confirm_reservation.lua"));
        script.setResultType(String.class);
        return script;
    }

    /**
     * 예약 취소 스크립트
     */
    @Bean
    public DefaultRedisScript<String> cancelScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/cancel_reservation.lua"));
        script.setResultType(String.class);
        return script;
    }
}
