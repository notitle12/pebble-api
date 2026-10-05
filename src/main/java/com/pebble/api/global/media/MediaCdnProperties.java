package com.pebble.api.global.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pebble.media.cdn")
public record MediaCdnProperties(boolean enabled, String baseUrl, String signingKeyBase64) {
    @Override
    public String toString() { return "MediaCdnProperties[enabled=" + enabled + ", signingKey=redacted]"; }
}
