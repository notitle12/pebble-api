package com.pebble.api.admin.presentation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.application.AdminAccountService;
import com.pebble.api.admin.domain.AdminCredentials;
import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.AdminAccountAudit;
import com.pebble.api.admin.presentation.dto.AdminAccountPageResponse;
import com.pebble.api.admin.presentation.dto.AdminAccountResponse;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/admin-accounts")
public class AdminAccountController {
    private static final Set<String> LIST_QUERY = Set.of("page", "size", "sort");
    private static final Set<String> CREATE_FIELDS = Set.of("loginId", "initialPassword");
    private static final Set<String> STATUS_FIELDS = Set.of("status");
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt", "loginId");

    private final AdminAccountService accounts;
    private final AdminAccountAudit audit;
    private final ObjectMapper mapper;

    @GetMapping
    public ApiResponse<AdminAccountPageResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        long actorId = actorId(jwt);
        try {
            checkQuery(query, LIST_QUERY);
            noPayload(request);
            var page = accounts.list(actorId, pageable(query)).map(AdminAccountResponse::from);
            audit.record("ADMIN_ACCOUNT_LIST", "SUCCESS", actorId, null, request);
            return ApiResponse.of(AdminAccountPageResponse.from(page));
        } catch (ApplicationException exception) {
            audit.record("ADMIN_ACCOUNT_LIST", exception.error().code(), actorId, null, request);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record("ADMIN_ACCOUNT_LIST", "BACKEND_FAILURE", actorId, null, request);
            throw exception;
        }
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<ApiResponse<AdminAccountResponse>> create(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) String rawJson,
            HttpServletRequest request) {
        long actorId = actorId(jwt);
        Long targetId = null;
        try {
            checkQuery(query, Set.of());
            JsonNode body = parse(rawJson);
            checkFields(body, CREATE_FIELDS);
            JsonNode loginId = body.get("loginId");
            JsonNode password = body.get("initialPassword");
            if (loginId == null || !loginId.isTextual() || !AdminCredentials.validLoginId(loginId.textValue())
                    || password == null || !password.isTextual()
                    || !AdminCredentials.validInitialPassword(password.textValue())) throw invalid();
            var created = accounts.create(actorId, loginId.textValue(), password.textValue());
            targetId = created.getId();
            audit.record("ADMIN_ACCOUNT_CREATE", "SUCCESS", actorId, targetId, request);
            var response = AdminAccountResponse.from(created);
            var location = UriComponentsBuilder.fromPath("/api/v1/admin/admin-accounts/{adminId}")
                    .buildAndExpand(created.getId()).toUri();
            return ResponseEntity.created(location).body(ApiResponse.of(response));
        } catch (ApplicationException exception) {
            audit.record("ADMIN_ACCOUNT_CREATE", exception.error().code(), actorId, targetId, request);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record("ADMIN_ACCOUNT_CREATE", "BACKEND_FAILURE", actorId, targetId, request);
            throw exception;
        }
    }

    @PatchMapping(value = "/{adminId:[0-9]+}/status", consumes = "application/json")
    public ApiResponse<AdminAccountResponse> changeStatus(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String adminId, @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) String rawJson, HttpServletRequest request) {
        long actorId = actorId(jwt);
        Long targetId = null;
        String action = "ADMIN_ACCOUNT_STATUS";
        try {
            checkQuery(query, Set.of());
            targetId = parsePositiveId(adminId);
            JsonNode body = parse(rawJson);
            checkFields(body, STATUS_FIELDS);
            JsonNode value = body.get("status");
            if (value == null || !value.isTextual()) throw invalid();
            AdminStatus status;
            try { status = AdminStatus.valueOf(value.textValue()); }
            catch (IllegalArgumentException exception) { throw invalid(); }
            action = "ADMIN_ACCOUNT_STATUS_" + status.name();
            var changed = accounts.changeStatus(actorId, targetId, status);
            audit.record(action, "SUCCESS", actorId, targetId, request);
            return ApiResponse.of(AdminAccountResponse.from(changed));
        } catch (ApplicationException exception) {
            audit.record(action, exception.error().code(), actorId, targetId, request);
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

    private void checkQuery(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1) throw invalid();
        }
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
            if (value == null || !value.matches("[1-9][0-9]{0,18}"))
                throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    private void noPayload(HttpServletRequest request) {
        try {
            if (request.getInputStream().read() != -1) throw invalid();
        } catch (IOException exception) { throw invalid(); }
    }

    private ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
    private ApplicationException invalidToken() { return new ApplicationException(GlobalErrorCode.INVALID_TOKEN); }
}
