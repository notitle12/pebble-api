package com.pebble.api.global.media;

public interface R2ObjectStorage {
    void put(String key, byte[] data);

    byte[] get(String key, int maxBytes);

    void delete(String key);

    String signedUrl(String key);
}
