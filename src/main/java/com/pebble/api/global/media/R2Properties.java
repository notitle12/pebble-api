package com.pebble.api.global.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pebble.media.r2")
public record R2Properties(String endpoint, String accessKeyId, String secretAccessKey, String bucketName, String region) {
    @Override
    public String toString() {
        return "R2Properties[redacted]";
    }
}
