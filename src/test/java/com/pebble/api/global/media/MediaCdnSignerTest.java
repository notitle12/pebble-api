package com.pebble.api.global.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class MediaCdnSignerTest {
    private static final String KEY = "post/12345678-1234-1234-1234-123456789abc/thumbnail.webp";
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1800000000), ZoneOffset.UTC);
    private String secret() { byte[] value = new byte[32]; Arrays.fill(value, (byte) 7); return Base64.getEncoder().encodeToString(value); }
    private MediaCdnSigner signer(String base, String secret) { return new MediaCdnSigner(new MediaCdnProperties(true, base, secret), clock); }

    @Test
    void matchesWorkerHmacVectorWithFifteenMinuteExpiry() {
        assertThat(signer("https://images.pebble-log.com/", secret()).signedUrl(KEY))
                .isEqualTo("https://images.pebble-log.com/media/cG9zdC8xMjM0NTY3OC0xMjM0LTEyMzQtMTIzNC0xMjM0NTY3ODlhYmMvdGh1bWJuYWlsLndlYnA?expires=1800000900&signature=LrDBLgR_3JTFfgTBMYM9fm0k7uam1cMfZ9mSk3d7KvI");
    }

    @Test
    void profilePhotoUsesSameSignedCdnPolicy() {
        String key = "member/12345678-1234-1234-1234-123456789abc/profile.webp";
        assertThat(signer("https://images.pebble-log.com", secret()).signedUrl(key)).startsWith("https://images.pebble-log.com/media/");
        assertThatThrownBy(() -> signer("https://images.pebble-log.com", secret()).signedUrl(key.replace("profile.webp", "original.png")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blogLogoUsesSameSignedCdnPolicy() {
        String key="member/12345678-1234-1234-1234-123456789abc/blog-logo.webp";
        assertThat(signer("https://images.pebble-log.com",secret()).signedUrl(key)).startsWith("https://images.pebble-log.com/media/");
        assertThatThrownBy(()->signer("https://images.pebble-log.com",secret()).signedUrl(key.replace("blog-logo.webp","original.svg"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeOriginWeakSecretsAndUnexpectedObjectKeysWithoutEchoingSecrets() {
        for (String origin : java.util.List.of("http://images.example", "https://user:secret@images.example", "https://images.example/path", "https://images.example?token=secret", "https://images.example:8443")) {
            assertThatThrownBy(() -> signer(origin, secret())).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(origin).hasMessageNotContaining(secret());
        }
        for (String secret : java.util.List.of("invalid", Base64.getEncoder().encodeToString(new byte[16]), secret().replace("=", ""))) {
            assertThatThrownBy(() -> signer("https://images.example", secret)).isInstanceOf(IllegalStateException.class);
        }
        for (String key : java.util.List.of("../private", "post/------------------------------------/thumbnail.webp", "project/12345678-1234-1234-1234-123456789abc/original.png")) {
            assertThatThrownBy(() -> signer("https://images.example", secret()).signedUrl(key)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void cdnEnabledStorageKeepsS3WritesButSignsThroughWorker() {
        S3Client client = mock(S3Client.class); S3Presigner presigner = mock(S3Presigner.class);
        S3R2ObjectStorage storage = new S3R2ObjectStorage(client, presigner, "pebble-media", signer("https://images.pebble-log.com", secret()));
        assertThat(storage.signedUrl(KEY)).startsWith("https://images.pebble-log.com/media/");
        verifyNoInteractions(client, presigner);
    }
}
