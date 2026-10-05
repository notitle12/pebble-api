package com.pebble.api.global.media;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.time.Duration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class S3R2ObjectStorage implements R2ObjectStorage {
    private static final Duration SIGNED_URL_DURATION = Duration.ofMinutes(15);
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final MediaCdnSigner cdn;

    public S3R2ObjectStorage(S3Client client, S3Presigner presigner, String bucket) {
        this(client, presigner, bucket, null);
    }

    public S3R2ObjectStorage(S3Client client, S3Presigner presigner, String bucket, MediaCdnSigner cdn) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.cdn = cdn;
    }

    @Override
    public void put(String key, byte[] data) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/webp").build(), RequestBody.fromBytes(data));
        } catch (RuntimeException exception) {
            throw storageUnavailable();
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException exception) {
            throw storageUnavailable();
        }
    }

    @Override
    public String signedUrl(String key) {
        try {
            if (cdn != null) return cdn.signedUrl(key);
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(SIGNED_URL_DURATION)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build()).url().toString();
        } catch (RuntimeException exception) {
            throw storageUnavailable();
        }
    }

    private ApplicationException storageUnavailable() {
        return new ApplicationException(GlobalErrorCode.STORAGE_UNAVAILABLE);
    }
}
