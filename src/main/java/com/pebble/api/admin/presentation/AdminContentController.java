package com.pebble.api.admin.presentation;

import com.pebble.api.admin.application.AdminContentService;
import com.pebble.api.admin.infrastructure.AdminContentAudit;
import com.pebble.api.admin.presentation.dto.AdminContentPage;
import com.pebble.api.admin.presentation.dto.AdminPostResponse;
import com.pebble.api.admin.presentation.dto.AdminProjectResponse;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.project.domain.ProjectVisibility;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/{type:posts|projects}")
public class AdminContentController {
    private final AdminContentService contents;
    private final AdminContentAudit audit;

    @GetMapping
    public ApiResponse<?> list(@PathVariable String type, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        long actorId = actorId(jwt);
        return audited("LIST", type, actorId, null, request, ignored -> {
            checkQuery(query, Set.of("page", "size", "sort", "q", "visibilityStatus", "isBlocked", ownerKey(type)));
            noPayload(request);
            String q = search(query.getFirst("q"));
            Boolean blocked = bool(query.getFirst("isBlocked"));
            Long owner = query.containsKey(ownerKey(type)) ? id(query.getFirst(ownerKey(type))) : null;
            var page = pageable(query);
            return "posts".equals(type)
                    ? ApiResponse.of(AdminContentPage.from(contents.listPosts(actorId, q,
                        visibility(query.getFirst("visibilityStatus"), PostVisibility.class), blocked, owner, page).map(AdminPostResponse::from)))
                    : ApiResponse.of(AdminContentPage.from(contents.listProjects(actorId, q,
                        visibility(query.getFirst("visibilityStatus"), ProjectVisibility.class), blocked, owner, page).map(AdminProjectResponse::from)));
        });
    }

    @GetMapping("/{contentId:[0-9]+}")
    public ApiResponse<?> detail(@PathVariable String type, @PathVariable String contentId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        long actorId = actorId(jwt);
        return audited("DETAIL", type, actorId, contentId, request, target -> {
            checkQuery(query, Set.of());
            noPayload(request);
            return "posts".equals(type) ? ApiResponse.of(AdminPostResponse.from(contents.detailPost(actorId, target)))
                    : ApiResponse.of(AdminProjectResponse.from(contents.detailProject(actorId, target)));
        });
    }

    @PutMapping("/{contentId:[0-9]+}/block")
    public ApiResponse<?> block(@PathVariable String type, @PathVariable String contentId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        return changeBlock(type, contentId, jwt, query, request, true);
    }

    @DeleteMapping("/{contentId:[0-9]+}/block")
    public ApiResponse<?> unblock(@PathVariable String type, @PathVariable String contentId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        return changeBlock(type, contentId, jwt, query, request, false);
    }

    private ApiResponse<?> changeBlock(String type, String contentId, Jwt jwt, MultiValueMap<String, String> query,
                                      HttpServletRequest request, boolean blocked) {
        long actorId = actorId(jwt);
        return audited(blocked ? "BLOCK" : "UNBLOCK", type, actorId, contentId, request, target -> {
            checkQuery(query, Set.of());
            noPayload(request);
            return "posts".equals(type) ? ApiResponse.of(AdminPostResponse.from(contents.blockPost(actorId, target, blocked)))
                    : ApiResponse.of(AdminProjectResponse.from(contents.blockProject(actorId, target, blocked)));
        });
    }

    @DeleteMapping("/{contentId:[0-9]+}")
    public ResponseEntity<Void> delete(@PathVariable String type, @PathVariable String contentId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        long actorId = actorId(jwt);
        return audited("DELETE", type, actorId, contentId, request, target -> {
            checkQuery(query, Set.of());
            noPayload(request);
            if ("posts".equals(type)) contents.deletePost(actorId, target);
            else contents.deleteProject(actorId, target);
            return ResponseEntity.noContent().build();
        });
    }

    private <T> T audited(String action, String type, long actorId, String rawId, HttpServletRequest request, Function<Long, T> work) {
        Long target = null;
        String dataType = "posts".equals(type) ? "post" : "project";
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

    private String ownerKey(String type) { return "posts".equals(type) ? "authorId" : "ownerId"; }
    private <E extends Enum<E>> E visibility(String value, Class<E> type) {
        if (value == null) return null;
        try { return Enum.valueOf(type, value); }
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
        if (sort.length != 2 || !Set.of("createdAt", "updatedAt", "publishedAt").contains(sort[0])
                || !Set.of("asc", "desc").contains(sort[1])) throw invalid();
        return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(sort[1]), sort[0], "id"));
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
        }
        catch (NumberFormatException exception) { throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN); }
    }
    private String search(String value) {
        if (value == null) return null;
        String q = value.strip();
        if (q.isEmpty() || q.codePointCount(0, q.length()) > 200) throw invalid();
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '\0' || Character.isLowSurrogate(c)
                    || (Character.isHighSurrogate(c) && (i + 1 >= q.length() || !Character.isLowSurrogate(q.charAt(++i))))) throw invalid();
        }
        return q;
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
