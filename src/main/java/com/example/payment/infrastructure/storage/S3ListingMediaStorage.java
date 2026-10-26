package com.example.payment.infrastructure.storage;

import com.example.payment.application.service.ListingMediaStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "app.media.storage-provider", havingValue = "S3")
public class S3ListingMediaStorage implements ListingMediaStorage {

    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;
    private final ServerSideEncryption encryption;
    private final String kmsKeyId;

    public S3ListingMediaStorage(
            S3Client s3Client,
            @Value("${app.media.s3.bucket:}") String bucket,
            @Value("${app.media.s3.key-prefix:listing-media}") String keyPrefix,
            @Value("${app.media.s3.server-side-encryption:AES256}") String encryption,
            @Value("${app.media.s3.kms-key-id:}") String kmsKeyId) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("app.media.s3.bucket is required for S3 media storage.");
        }
        this.s3Client = s3Client;
        this.bucket = bucket.trim();
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.encryption = parseEncryption(encryption);
        this.kmsKeyId = trimToNull(kmsKeyId);
        if (this.encryption == ServerSideEncryption.AWS_KMS && this.kmsKeyId == null) {
            throw new IllegalStateException("app.media.s3.kms-key-id is required when AWS_KMS encryption is selected.");
        }
    }

    @Override
    public String providerName() {
        return "S3";
    }

    @Override
    public void store(String storageKey, Path source, String contentType) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(fullKey(storageKey))
                .contentType(contentType)
                .serverSideEncryption(encryption);
        if (kmsKeyId != null) {
            request.ssekmsKeyId(kmsKeyId);
        }
        s3Client.putObject(request.build(), RequestBody.fromFile(source));
    }

    @Override
    public Resource load(String storageKey) {
        ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(fullKey(storageKey))
                .build());
        return new InputStreamResource(response);
    }

    @Override
    public void delete(String storageKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(fullKey(storageKey))
                .build());
    }

    @Override
    public StorageReadiness readiness() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return new StorageReadiness(true, "S3 media bucket is reachable with server-side encryption configured.");
        } catch (RuntimeException exception) {
            return new StorageReadiness(false, "S3 media bucket is not reachable: " + exception.getClass().getSimpleName());
        }
    }

    private String fullKey(String storageKey) {
        String normalized = storageKey.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../")) {
            throw new IllegalArgumentException("Invalid media storage key.");
        }
        return keyPrefix.isEmpty() ? normalized : keyPrefix + "/" + normalized;
    }

    private String normalizePrefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("^/+|/+$", "");
    }

    private ServerSideEncryption parseEncryption(String value) {
        if ("AES256".equalsIgnoreCase(value)) {
            return ServerSideEncryption.AES256;
        }
        if ("AWS_KMS".equalsIgnoreCase(value) || "aws:kms".equalsIgnoreCase(value)) {
            return ServerSideEncryption.AWS_KMS;
        }
        throw new IllegalStateException("Media S3 encryption must be AES256 or AWS_KMS.");
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
