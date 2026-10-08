package com.pebble.api.global.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pebble.api.global.exception.ApplicationException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3R2ObjectStorageTest {
    private static final String PRIVATE_BUCKET = "pebble-media";
    private static final String PRIVATE_KEY = "generated/key.webp";

    @Test
    void storesWebpDeletesAndCreatesFifteenMinuteGetUrl() {
        S3Client client = mock(S3Client.class);
        try (S3Presigner presigner = presigner()) {
            S3R2ObjectStorage storage = new S3R2ObjectStorage(client, presigner, PRIVATE_BUCKET);
            byte[] bytes = {1, 2, 3};

            storage.put(PRIVATE_KEY, bytes);
            storage.delete(PRIVATE_KEY);
            String url = storage.signedUrl(PRIVATE_KEY);

            ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(client).putObject(put.capture(), any(RequestBody.class));
            assertThat(put.getValue().bucket()).isEqualTo(PRIVATE_BUCKET);
            assertThat(put.getValue().key()).isEqualTo(PRIVATE_KEY);
            assertThat(put.getValue().contentType()).isEqualTo("image/webp");

            ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(client).deleteObject(delete.capture());
            assertThat(delete.getValue().bucket()).isEqualTo(PRIVATE_BUCKET);
            assertThat(delete.getValue().key()).isEqualTo(PRIVATE_KEY);

            URI signed = URI.create(url);
            assertThat(signed.getHost()).isEqualTo("example.r2.cloudflarestorage.com");
            assertThat(signed.getRawQuery()).contains("X-Amz-Expires=900", "X-Amz-Signature=");
        }
    }

    @Test
    void readsSavedObjectBytesFromThePrivateBucket() {
        S3Client client = mock(S3Client.class);
        byte[] expected = {4, 5, 6};
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(expected));

        try (S3Presigner presigner = presigner()) {
            S3R2ObjectStorage storage = new S3R2ObjectStorage(client, presigner, PRIVATE_BUCKET);
            assertThat(storage.get(PRIVATE_KEY, 10)).containsExactly(expected);
        }

        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(get.capture());
        assertThat(get.getValue().bucket()).isEqualTo(PRIVATE_BUCKET);
        assertThat(get.getValue().key()).isEqualTo(PRIVATE_KEY);
        byte[] tooLarge = new byte[11];
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(tooLarge));
        try (S3Presigner presigner = presigner()) {
            S3R2ObjectStorage storage = new S3R2ObjectStorage(client, presigner, PRIVATE_BUCKET);
            assertThatThrownBy(() -> storage.get(PRIVATE_KEY, 10))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessage("이미지 저장소를 사용할 수 없습니다.");
        }
    }

    @Test
    void storageFailuresDoNotExposeProviderMessagesOrSignedUrls() {
        S3Client client = mock(S3Client.class);
        doThrow(SdkClientException.create("secret=https://provider.invalid/?token=private-token"))
                .when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        doThrow(SdkClientException.create("secret=https://provider.invalid/?token=private-token"))
                .when(client).deleteObject(any(DeleteObjectRequest.class));
        doThrow(SdkClientException.create("secret=https://provider.invalid/?token=private-token"))
                .when(client).getObject(any(GetObjectRequest.class));

        try (S3Presigner presigner = presigner()) {
            S3R2ObjectStorage storage = new S3R2ObjectStorage(client, presigner, PRIVATE_BUCKET);
            assertSafeStorageError(() -> storage.put(PRIVATE_KEY, new byte[]{1}));
            assertSafeStorageError(() -> storage.delete(PRIVATE_KEY));
            assertSafeStorageError(() -> storage.get(PRIVATE_KEY, 10));
        }
    }

    private void assertSafeStorageError(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ApplicationException.class)
                .hasMessage("이미지 저장소를 사용할 수 없습니다.")
                .hasNoCause()
                .hasMessageNotContaining("provider.invalid").hasMessageNotContaining("private-token").hasMessageNotContaining(PRIVATE_KEY);
    }

    private S3Presigner presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create("https://example.r2.cloudflarestorage.com"))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private ResponseInputStream<GetObjectResponse> response(byte[] bytes) {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes), () -> {}));
    }
}
