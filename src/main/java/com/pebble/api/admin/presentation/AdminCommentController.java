package com.pebble.api.admin.presentation;

import com.pebble.api.admin.application.AdminCommentService;
import com.pebble.api.admin.infrastructure.AdminContentAudit;
import com.pebble.api.admin.presentation.dto.AdminCommentResponse;
import com.pebble.api.admin.presentation.dto.AdminContentPage;
import com.pebble.api.comment.domain.CommentTarget;
import com.pebble.api.comment.domain.CommentVisibility;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/{type:post-comments|project-comments}")
public class AdminCommentController {
    private final AdminCommentService comments;
    private final AdminContentAudit audit;

    @GetMapping
    public ApiResponse<?> list(@PathVariable String type, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        long actorId = actorId(jwt);
        return audited("LIST", type, actorId, null, request, ignored -> {
            checkQuery(query, Set.of("page", "size", "sort", contentKey(type), "authorId", "visibility", "deleted"));
            noPayload(request);
            Long contentId = query.containsKey(contentKey(type)) ? id(query.getFirst(contentKey(type))) : null;
            Long authorId = query.containsKey("authorId") ? id(query.getFirst("authorId")) : null;
            CommentVisibility visibility = visibility(query.getFirst("visibility"));
            Boolean deleted = bool(query.getFirst("deleted"));
            return ApiResponse.of(AdminContentPage.from(comments.list(actorId, target(type), contentId, authorId,
                    visibility, deleted, pageable(query)).map(AdminCommentResponse::from)));
        });
    }

    @DeleteMapping("/{commentId:[0-9]+}")
    public ResponseEntity<Void> delete(@PathVariable String type, @PathVariable String commentId,
            @AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request) {
        long actorId = actorId(jwt);
        return audited("DELETE", type, actorId, commentId, request, id -> {
            checkQuery(query, Set.of());
            noPayload(request);
            comments.delete(actorId, target(type), id);
            return ResponseEntity.noContent().build();
        });
    }

    private <T> T audited(String action, String type, long actorId, String rawId, HttpServletRequest request,
                          Function<Long, T> work) {
        Long target = null;
        String dataType = "post-comments".equals(type) ? "post_comment" : "project_comment";
        String operation = "ADMIN_" + dataType.toUpperCase(java.util.Locale.ROOT) + "_" + action;
        try {
            target = rawId == null ? null : id(rawId);
            T result = work.apply(target);
            audit.record(operation, "SUCCESS", dataType, actorId, target, request);
            return result;
        } catch (ApplicationException exception) {
            audit.record(operation, exception.error().code(), dataType, actorId, target, request);
            throw exception;
        } catch (RuntimeException exception) {
            audit.record(operation, "BACKEND_FAILURE", dataType, actorId, target, request);
            throw exception;
        }
    }

    private String contentKey(String type) { return "post-comments".equals(type) ? "postId" : "projectId"; }
    private CommentTarget target(String type) { return "post-comments".equals(type) ? CommentTarget.POST : CommentTarget.PROJECT; }
    private CommentVisibility visibility(String value) {
        if (value == null) return null;
        try { return CommentVisibility.valueOf(value); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    private Boolean bool(String value) {
        if (value == null) return null;
        if (!Set.of("true", "false").contains(value)) throw invalid();
        return Boolean.valueOf(value);
    }
    private PageRequest pageable(MultiValueMap<String, String> query) {
        int page = integer(query.getFirst("page"), 0);
        int size = integer(query.getFirst("size"), 20);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw invalid();
        String[] sort = query.containsKey("sort") ? query.getFirst("sort").split(",", -1) : new String[]{"createdAt", "desc"};
        if (sort.length != 2 || !"createdAt".equals(sort[0]) || !Set.of("asc", "desc").contains(sort[1])) throw invalid();
        return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(sort[1]), "createdAt", "id"));
    }
    private int integer(String value, int fallback) {
        if (value == null) return fallback;
        try { if (!value.matches("[0-9]+")) throw new NumberFormatException(); return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw invalid(); }
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
        try {
            long id = Long.parseLong(jwt.getSubject().substring(6));
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) { throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN); }
    }
    private void checkQuery(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1) throw invalid();
    }
    private void noPayload(HttpServletRequest request) {
        try { if (request.getInputStream().read() != -1) throw invalid(); }
        catch (IOException exception) { throw invalid(); }
    }
    private ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
}
