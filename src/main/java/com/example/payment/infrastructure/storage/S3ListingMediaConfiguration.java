package com.example.payment.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "app.media.storage-provider", havingValue = "S3")
public class S3ListingMediaConfiguration {

    @Bean(destroyMethod = "close")
    S3Client listingMediaS3Client(
            @Value("${app.media.s3.region:ap-northeast-2}") String region,
            @Value("${app.media.s3.endpoint:}") String endpoint,
            @Value("${app.media.s3.path-style-access:false}") boolean pathStyleAccess,
            @Value("${app.media.s3.access-key:}") String accessKey,
            @Value("${app.media.s3.secret-key:}") String secretKey) {
        if (hasText(accessKey) != hasText(secretKey)) {
            throw new IllegalStateException("Both media S3 access key and secret key must be provided together.");
        }

        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build())
                .credentialsProvider(hasText(accessKey)
                        ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                        : DefaultCredentialsProvider.create());
        if (hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
