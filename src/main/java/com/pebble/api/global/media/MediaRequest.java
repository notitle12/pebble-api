package com.pebble.api.global.media;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** 공통 multipart 기술 입력만 검증하며 콘텐츠 권한은 각 Feature가 확인한다. */
public final class MediaRequest {
    private MediaRequest() { }
    public static byte[] file(MultipartHttpServletRequest request, Set<String> fields) {
        noQuery(request);
        var files = request.getMultiFileMap();
        if (files.size() != 1 || !files.containsKey("file") || files.get("file").size() != 1) throw invalid();
        for (var entry : request.getParameterMap().entrySet()) {
            if (!fields.contains(entry.getKey()) || entry.getValue().length != 1) throw invalid();
        }
        if (request.getParameterMap().containsKey("file")) throw invalid();
        MultipartFile file = files.getFirst("file");
        if (file == null || file.isEmpty()) throw invalid();
        if (file.getSize() > 10 * 1024 * 1024) throw new ApplicationException(GlobalErrorCode.MEDIA_TOO_LARGE);
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw invalid();
        }
    }

    public static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
    }

    public static String text(String value) {
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) > 300) throw invalid();
        for (int i = 0; i < value.length();) {
            int point = value.codePointAt(i);
            if (point == 0 || point >= 0xd800 && point <= 0xdfff) throw invalid();
            i += Character.charCount(point);
        }
        return value;
    }

    public static int order(String value) {
        try {
            if (value == null || !value.matches("[0-9]+")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    public static ApplicationException invalid() {
        return new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
    }
}
