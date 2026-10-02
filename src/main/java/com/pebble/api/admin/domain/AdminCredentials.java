package com.pebble.api.admin.domain;

/** 초기 MASTER와 새 MANAGER 계정에 적용하는 동일한 자격 증명 규칙. */
public final class AdminCredentials {
    private AdminCredentials() { }

    public static boolean validLoginId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,99}");
    }

    public static boolean validInitialPassword(String value) {
        if (value == null || value.isBlank()) return false;
        int count = value.codePointCount(0, value.length());
        if (count < 12 || count > 128) return false;
        for (int i = 0; i < value.length();) {
            int point = value.codePointAt(i);
            if (Character.isISOControl(point) || point >= 0xD800 && point <= 0xDFFF) return false;
            i += Character.charCount(point);
        }
        return true;
    }
}
