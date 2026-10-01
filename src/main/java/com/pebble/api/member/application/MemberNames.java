package com.pebble.api.member.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import java.text.Normalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 기본 닉네임 할당과 직접 설정에서 같은 이름 공간을 사용한다. */
@Component
@RequiredArgsConstructor
public class MemberNames {
    private final MemberRepository members;
    private final JdbcTemplate jdbc;

    public void lockNames() {
        // 가입·수동 변경·중복 확인을 같은 트랜잭션 잠금으로 조정한다. DB 고유 제약도 유지한다.
        jdbc.queryForList("select pg_advisory_xact_lock(726381025)");
    }

    public String initialNickname(String provided) {
        String base = provided == null || provided.isBlank() ? "pebble" : Normalizer.normalize(provided.strip(), Normalizer.Form.NFC);
        validateUnicode(base, "nickname");
        String candidate = truncate(base, 30);
        for (int suffix = 2; members.existsByNickname(candidate); suffix++) {
            String ending = "-" + suffix;
            candidate = truncate(base, 30 - ending.length()) + ending;
        }
        return candidate;
    }

    public static String normalize(String value, int limit, String field) {
        if (value == null) throw invalid(field);
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        validateUnicode(normalized, field);
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > limit) throw invalid(field);
        return normalized;
    }

    private static void validateUnicode(String value, String field) {
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            if (point == 0 || (point >= 0xD800 && point <= 0xDFFF)) throw invalid(field);
            offset += Character.charCount(point);
        }
    }

    private String truncate(String value, int limit) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(limit, value.codePointCount(0, value.length()))));
    }

    private static ApplicationException invalid(String field) {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, "비어 있지 않은 허용 길이의 문자열을 입력해 주세요.");
    }
}
