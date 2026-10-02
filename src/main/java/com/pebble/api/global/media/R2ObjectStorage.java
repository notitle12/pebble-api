package com.pebble.api.global.media;

public interface R2ObjectStorage {
    void put(String key, byte[] data);

    void delete(String key);

    String signedUrl(String key);
}
