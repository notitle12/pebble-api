package com.pebble.api.global.media;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 공개 판정을 통과한 저장 키에만 기존 15분 수명의 CDN 접근 서명을 발급한다. */
public final class MediaCdnSigner {
    private final String origin;
    private final byte[] secret;
    private final Clock clock;

    public MediaCdnSigner(MediaCdnProperties properties, Clock clock) {
        try {
            URI uri = URI.create(properties.baseUrl());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() != -1
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) throw new IllegalArgumentException();
            this.origin = "https://" + uri.getHost().toLowerCase(Locale.ROOT);
            this.secret = Base64.getDecoder().decode(properties.signingKeyBase64());
            if (secret.length != 32 || !Base64.getEncoder().encodeToString(secret).equals(properties.signingKeyBase64())) {
                throw new IllegalArgumentException();
            }
            this.clock = clock;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Media CDN requires an HTTPS origin and a canonical Base64 32-byte signing key");
        }
    }

    public String signedUrl(String key) {
        if (key == null || !key.matches("(?:member/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:profile|blog-logo)|post/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:thumbnail|body)|project/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:display|thumbnail))\\.webp")) {
            throw new IllegalArgumentException("Invalid media key");
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
        long expires = clock.instant().getEpochSecond() + 900;
        String message = "v1\n" + origin + "\n" + encoded + "\n" + expires;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            return origin + "/media/" + encoded + "?expires=" + expires + "&signature=" + signature;
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign media URL");
        }
    }
}
