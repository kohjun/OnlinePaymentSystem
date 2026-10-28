package com.example.payment.infrastructure.config;

import com.example.payment.infrastructure.persistence.redis.config.RedisConfig;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 운영과 같은 조합의 매퍼: Boot 자동 구성 + 운영 application.yml의 spring.jackson.* +
 * JacksonConfig + RedisConfig가 등록하는 Redis 전용 매퍼.
 */
final class ApplicationJsonMappers {

    private ApplicationJsonMappers() {
    }

    static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(mainApplicationYaml()))
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .withBean("redisObjectMapper", JsonMapper.class, () -> new RedisConfig().redisObjectMapper());
    }

    // 테스트 클래스패스의 application.yml이 운영 파일을 가리므로 파일에서 직접 읽는다.
    private static PropertySource<?> mainApplicationYaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("main-application-yml", new FileSystemResource("src/main/resources/application.yml"))
                    .get(0);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
