package com.pebble.api.global.media;

public record ProcessedImage(byte[] display, byte[] thumbnail) {
    public ProcessedImage {
        display = display.clone();
        thumbnail = thumbnail.clone();
    }

    @Override
    public byte[] display() {
        return display.clone();
    }

    @Override
    public byte[] thumbnail() {
        return thumbnail.clone();
    }
}
