package com.pebble.api.admin.presentation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.application.AdminMemberService;
import com.pebble.api.admin.infrastructure.AdminMemberAudit;
import com.pebble.api.admin.presentation.dto.AdminMemberPageResponse;
import com.pebble.api.admin.presentation.dto.AdminMemberResponse;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.domain.MemberStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/members")
public class AdminMemberController {
    private static final Set<String> LIST_QUERY = Set.of("page", "size", "sort", "status", "q");
    private static final Set<String> STATUS_FIELDS = Set.of("status");
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt", "nickname", "blogName", "handle");

    private final AdminMemberService members;
    private final AdminMemberAudit audit;
    private final ObjectMapper mapper;

    @GetMapping
    public ApiResponse<AdminMemberPageResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        long actorId = actorId(jwt);
        String action = "ADMIN_MEMBER_LIST";
        try {
            checkQuery(query, LIST_QUERY);
            noPayload(request);
            MemberStatus status = parseFilterStatus(query.getFirst("status"));
            String q = search(query.getFirst("q"));
            var page = members.list(actorId, status, q, pageable(query)).map(AdminMemberResponse::from);
            audit.record(action, "SUCCESS", actorId, null, request);
            return ApiResponse.of(AdminMemberPageResponse.from(page));
        } catch (ApplicationException exception) {
            audit.record(isInvalid(exception) ? "ADMIN_MEMBER_INVALID" : action, exception.error().code(), actorId, null, request);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record(action, "BACKEND_FAILURE", actorId, null, request);
            throw exception;
        }
    }

    @GetMapping("/{memberId:[0-9]+}")
    public ApiResponse<AdminMemberResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String memberId,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        long actorId = actorId(jwt);
        Long targetId = null;
        String action = "ADMIN_MEMBER_DETAIL";
        try {
            checkQuery(query, Set.of());
            noPayload(request);
            targetId = parsePositiveId(memberId);
            var member = members.detail(actorId, targetId);
            audit.record(action, "SUCCESS", actorId, targetId, request);
            return ApiResponse.of(AdminMemberResponse.from(member));
        } catch (ApplicationException exception) {
            audit.record(isInvalid(exception) ? "ADMIN_MEMBER_INVALID" : action, exception.error().code(), actorId, targetId, request);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record(action, "BACKEND_FAILURE", actorId, targetId, request);
            throw exception;
        }
    }

    @PatchMapping(value = "/{memberId:[0-9]+}/status", consumes = "application/json")
    public ApiResponse<AdminMemberResponse> changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable String memberId,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) String rawJson,
            HttpServletRequest request) {
        long actorId = actorId(jwt);
        Long targetId = null;
        String action = "ADMIN_MEMBER_STATUS";
        try {
            checkQuery(query, Set.of());
            targetId = parsePositiveId(memberId);
            JsonNode body = parse(rawJson);
            checkFields(body, STATUS_FIELDS);
            JsonNode value = body.get("status");
            if (value == null || !value.isTextual()) throw invalid();
            MemberStatus status;
            try { status = MemberStatus.valueOf(value.textValue()); }
            catch (IllegalArgumentException exception) { throw invalid(); }
            if (status != MemberStatus.ACTIVE && status != MemberStatus.SUSPENDED) throw invalid();
            action = "ADMIN_MEMBER_STATUS_" + status.name();
            var changed = members.changeStatus(actorId, targetId, status);
            audit.record(action, "SUCCESS", actorId, targetId, request);
            return ApiResponse.of(AdminMemberResponse.from(changed));
        } catch (ApplicationException exception) {
            audit.record(isInvalid(exception) ? "ADMIN_MEMBER_INVALID" : action, exception.error().code(), actorId, targetId, request);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record(action, "BACKEND_FAILURE", actorId, targetId, request);
            throw exception;
        }
    }

    private PageRequest pageable(MultiValueMap<String, String> query) {
        int page = integer(query.getFirst("page"), 0);
        int size = integer(query.getFirst("size"), 20);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw invalid();
        String[] parts = query.getFirst("sort") == null ? new String[]{"createdAt", "desc"}
                : query.getFirst("sort").split(",", -1);
        if (parts.length != 2 || !SORT_FIELDS.contains(parts[0]) || !Set.of("asc", "desc").contains(parts[1]))
            throw invalid();
        Sort.Direction direction = Sort.Direction.fromString(parts[1]);
        return PageRequest.of(page, size, Sort.by(direction, parts[0], "id"));
    }

    private int integer(String value, int fallback) {
        if (value == null) return fallback;
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    private MemberStatus parseFilterStatus(String value) {
        if (value == null) return null;
        try {
            MemberStatus status = MemberStatus.valueOf(value);
            return status;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private String search(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 200) throw invalid();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\0' || Character.isLowSurrogate(c)
                    || (Character.isHighSurrogate(c) && (i + 1 >= trimmed.length() || !Character.isLowSurrogate(trimmed.charAt(++i)))))
                throw invalid();
        }
        return trimmed;
    }

    private void checkQuery(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1) throw invalid();
    }

    private JsonNode parse(String rawJson) {
        try {
            JsonNode body = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(rawJson == null ? "" : rawJson);
            if (body == null || !body.isObject()) throw invalid();
            return body;
        } catch (JsonProcessingException exception) { throw invalid(); }
    }

    private void checkFields(JsonNode body, Set<String> fields) {
        body.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) throw invalid(); });
        for (String field : fields) if (!body.has(field)) throw invalid();
    }

    private long actorId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || !jwt.getSubject().startsWith("admin:")) throw invalidToken();
        try {
            long id = Long.parseLong(jwt.getSubject().substring("admin:".length()));
            if (id <= 0) throw invalidToken();
            return id;
        } catch (NumberFormatException exception) { throw invalidToken(); }
    }

    private long parsePositiveId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    private void noPayload(HttpServletRequest request) {
        try { if (request.getInputStream().read() != -1) throw invalid(); }
        catch (IOException exception) { throw invalid(); }
    }

    private boolean isInvalid(ApplicationException exception) { return exception.error() == GlobalErrorCode.INVALID_REQUEST; }
    private ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
    private ApplicationException invalidToken() { return new ApplicationException(GlobalErrorCode.INVALID_TOKEN); }
}
