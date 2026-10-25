package com.example.payment.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ListingMediaStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void requiresBucketConfiguration() {
        assertThrows(IllegalStateException.class,
                () -> new S3ListingMediaStorage(mock(S3Client.class), "", "listing-media", "AES256", ""));
    }

    @Test
    void storesPrivateObjectUnderPrefixWithServerSideEncryption() throws Exception {
        S3Client client = mock(S3Client.class);
        S3ListingMediaStorage storage = new S3ListingMediaStorage(
                client, "everysale-private", "listing-media", "AES256", "");
        Path source = tempDir.resolve("image.png");
        Files.write(source, new byte[]{1, 2, 3});

        storage.store("LIST-1/PRODUCT_IMAGE/MEDIA-1.png", source, "image/png");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertEquals("everysale-private", request.getValue().bucket());
        assertEquals("listing-media/LIST-1/PRODUCT_IMAGE/MEDIA-1.png", request.getValue().key());
        assertEquals(ServerSideEncryption.AES256, request.getValue().serverSideEncryption());
        assertEquals("image/png", request.getValue().contentType());
    }

    @Test
    void readinessFailsClosedWhenBucketCannotBeReached() {
        S3Client client = mock(S3Client.class);
        when(client.headBucket(any(HeadBucketRequest.class))).thenThrow(S3Exception.builder().message("denied").build());
        S3ListingMediaStorage storage = new S3ListingMediaStorage(
                client, "everysale-private", "listing-media", "AES256", "");

        var readiness = storage.readiness();

        assertFalse(readiness.ready());
        assertEquals("S3 media bucket is not reachable: S3Exception", readiness.message());
    }
}
