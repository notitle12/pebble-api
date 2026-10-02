package com.pebble.api.admin.presentation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.application.AdminClassificationService;
import com.pebble.api.admin.infrastructure.AdminContentAudit;
import com.pebble.api.admin.presentation.dto.AdminClassificationWriteRequest;
import com.pebble.api.category.application.CategoryQueryService;
import com.pebble.api.category.domain.Category;
import com.pebble.api.category.presentation.dto.CategoryResponse;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.presentation.dto.TagResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/v1/admin/{type:categories|tags}")
public class AdminClassificationController {
    private final AdminClassificationService classifications;
    private final AdminContentAudit audit;
    private final ObjectMapper mapper;

    @GetMapping
    public ApiResponse<?> list(@PathVariable String type, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        long actor = actorId(jwt);
        return audited("LIST", type, actor, null, request, ignored -> {
            checkQuery(query);
            noPayload(request);
            return "categories".equals(type)
                    ? ApiResponse.of(classifications.listCategories(actor).stream().map(AdminClassificationController::category).toList())
                    : ApiResponse.of(classifications.listTags(actor).stream().map(AdminClassificationController::tag).toList());
        }, ignored -> null);
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<ApiResponse<?>> create(@PathVariable String type, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) String raw,
            HttpServletRequest request) {
        long actor = actorId(jwt);
        return audited("CREATE", type, actor, null, request, ignored -> {
            checkQuery(query);
            JsonNode body = parse(raw);
            if ("categories".equals(type)) {
                var created = classifications.createCategory(actor, AdminClassificationWriteRequest.category(body, true));
                long id = created.category().getId();
                return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/admin/categories/{id}").buildAndExpand(id).toUri())
                        .body((ApiResponse<?>) ApiResponse.of(category(created)));
            }
            Tag created = classifications.createTag(actor, AdminClassificationWriteRequest.tag(body, true));
            long id = created.getId();
            return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/admin/tags/{id}").buildAndExpand(id).toUri())
                    .body((ApiResponse<?>) ApiResponse.of(tag(created)));
        }, this::createdId);
    }

    @PatchMapping(value = "/{id:[0-9]+}", consumes = "application/json")
    public ApiResponse<?> update(@PathVariable String type, @PathVariable String id, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) String raw,
            HttpServletRequest request) {
        long actor = actorId(jwt);
        return audited("UPDATE", type, actor, id, request, target -> {
            checkQuery(query);
            JsonNode body = parse(raw);
            return "categories".equals(type)
                    ? ApiResponse.of(category(classifications.updateCategory(actor, target, AdminClassificationWriteRequest.category(body, false))))
                    : ApiResponse.of(tag(classifications.updateTag(actor, target, AdminClassificationWriteRequest.tag(body, false))));
        }, ignored -> null);
    }

    private static CategoryResponse category(CategoryQueryService.CategoryBranch branch) {
        Category root = branch.category();
        List<CategoryResponse> children = branch.children().stream().map(child -> category(child, List.of())).toList();
        return category(root, children);
    }
    private static CategoryResponse category(Category value, List<CategoryResponse> children) {
        return new CategoryResponse(value.getId().toString(), value.getParent() == null ? null : value.getParent().getId().toString(),
                value.getName(), value.getSlug(), value.getDisplayOrder(), value.getStatus(), children);
    }
    private static TagResponse tag(Tag value) {
        return new TagResponse(value.getId().toString(), value.getName(), value.getSlug(), value.getDisplayOrder(), value.getStatus());
    }

    private <T> T audited(String action, String type, long actor, String rawId, HttpServletRequest request,
            Function<Long, T> work, Function<T, Long> resultTarget) {
        String dataType = "categories".equals(type) ? "category" : "tag";
        String operation = "ADMIN_" + dataType.toUpperCase(java.util.Locale.ROOT) + "_" + action;
        Long target = null;
        try {
            target = rawId == null ? null : id(rawId);
            T result = work.apply(target);
            Long resultId = resultTarget.apply(result);
            audit.record(operation, "SUCCESS", dataType, actor, resultId == null ? target : resultId, request);
            return result;
        } catch (ApplicationException exception) {
            audit.record(operation, exception.error().code(), dataType, actor, target, request);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record(operation, "BACKEND_FAILURE", dataType, actor, target, request);
            throw exception;
        }
    }
    private Long createdId(ResponseEntity<ApiResponse<?>> response) {
        var location = response.getHeaders().getLocation();
        if (location == null || location.getPath() == null) return null;
        String path = location.getPath();
        return id(path.substring(path.lastIndexOf('/') + 1));
    }
    private JsonNode parse(String raw) {
        try {
            JsonNode node = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw == null ? "" : raw);
            if (node == null || !node.isObject()) throw invalid();
            return node;
        } catch (JsonProcessingException exception) { throw invalid(); }
    }
    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) { throw invalid(); }
    }
    private long actorId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || !jwt.getSubject().startsWith("admin:"))
            throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
        try { long actor = Long.parseLong(jwt.getSubject().substring(6)); if (actor > 0) return actor; }
        catch (NumberFormatException ignored) { }
        throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
    }
    private void checkQuery(MultiValueMap<String, String> query) {
        for (var entry : query.entrySet()) if (entry.getValue().size() != 1) throw invalid();
        if (!query.isEmpty()) throw invalid();
    }
    private void noPayload(HttpServletRequest request) {
        try { if (request.getInputStream().read() != -1) throw invalid(); }
        catch (IOException exception) { throw invalid(); }
    }
    private ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
}
