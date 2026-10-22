package com.pebble.api.update.application;
import com.pebble.api.update.domain.*;
import com.pebble.api.global.exception.*;

public record UpdateInput(String title, String summary, String body, UpdateType type, String version, UpdateStatus status) {
    public UpdateInput {
        title = text(title, 1, 160); summary = text(summary, 0, 300); body = text(body, 1, 20000);
        if (version != null) { version = text(version, 0, 40); if (version.isEmpty()) version = null; }
        if (type == null || status == null) throw invalid();
    }
    private static String text(String value, int min, int max) {
        if (value == null) throw invalid();
        value = value.strip();
        int count = value.codePointCount(0, value.length());
        if (count < min || count > max) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) throw invalid();
            if (Character.isHighSurrogate(c) && (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))) throw invalid();
        }
        return value;
    }
    private static ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
}
