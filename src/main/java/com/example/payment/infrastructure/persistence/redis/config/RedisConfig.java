package com.example.payment.infrastructure.persistence.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 단일 모드 설정
 * - 클러스터 모드 대신 단일 Redis 인스턴스 사용
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

    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL
        );
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
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper redisObjectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serialization
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value serialization
        Jackson2JsonRedisSerializer<Object> jacksonSerializer = new Jackson2JsonRedisSerializer<>(redisObjectMapper, Object.class);
        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);

        // 단일 모드에서는 트랜잭션 지원 가능
        template.setEnableTransactionSupport(false);

        template.afterPropertiesSet();
        return template;
    }

    // ==================== Lua Script Beans 추가 ====================

    /**
     * 재고 예약 스크립트
     */
    @Bean
    public DefaultRedisScript<String> reserveScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/reserve_resource.lua"));
        script.setResultType(String.class);  // JSON 문자열로 반환
        return script;
    }

    /**
     * 예약 확정 스크립트
     */
    @Bean
    public DefaultRedisScript<String> confirmScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/confirm_reservation.lua"));
        script.setResultType(String.class);  // JSON 문자열로 반환
        return script;
    }

    /**
     * 예약 취소 스크립트
     */
    @Bean
    public DefaultRedisScript<String> cancelScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/cancel_reservation.lua"));
        script.setResultType(String.class);  // JSON 문자열로 반환
        return script;
    }

    /**
     * 릴리스 스크립트 (필요한 경우)
     */
    @Bean
    public DefaultRedisScript<String> releaseScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/cancel_reservation.lua")); // 또는 별도 release 스크립트
        script.setResultType(String.class);
        return script;
    }
}