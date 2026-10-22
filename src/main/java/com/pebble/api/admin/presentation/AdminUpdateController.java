package com.pebble.api.admin.presentation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.application.AdminUpdateService;
import com.pebble.api.admin.infrastructure.AdminContentAudit;
import com.pebble.api.global.exception.*;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.update.presentation.*;
import com.pebble.api.update.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/updates")
public class AdminUpdateController {
    private static final Set<String> CREATE = Set.of("title", "summary", "body", "type", "version", "status");
    private static final Set<String> REPLACE = Set.of("title", "summary", "body", "type", "version", "status", "expectedRevision");
    private final AdminUpdateService updates;
    private final ObjectMapper mapper;
    private final AdminContentAudit audit;
    @GetMapping
    public ApiResponse<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String,String> query) {
        UpdateRequest.query(query, Set.of("page", "size", "type", "status"));
        var page = updates.list(actor(jwt), UpdateRequest.enumValue(UpdateType.class, query.getFirst("type")),
            UpdateRequest.enumValue(UpdateStatus.class, query.getFirst("status")), UpdateRequest.page(query), UpdateRequest.size(query));
        return ApiResponse.of(UpdateResponse.Listing.of(page, page.getContent().stream().map(UpdateResponse.ManagementSummary::of).toList()));
    }
    @GetMapping("/{id:[0-9]+}")
    public ApiResponse<?> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam MultiValueMap<String,String> query) {
        UpdateRequest.query(query, Set.of());
        return ApiResponse.of(UpdateResponse.ManagementDetail.of(updates.detail(actor(jwt), UpdateRequest.id(id))));
    }
    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @RequestBody(required=false) String raw,
            @RequestParam MultiValueMap<String,String> query, HttpServletRequest request) {
        long actor = actor(jwt);
        return audited("CREATE", actor, null, request, () -> {
            UpdateRequest.query(query, Set.of());
            var value = updates.create(actor, UpdateRequest.input(UpdateRequest.parse(mapper, raw, CREATE)));
            audit.record("ADMIN_UPDATE_CREATED_ID", "SUCCESS", "serviceUpdate", actor, value.getId(), request);
            return ResponseEntity.created(URI.create("/api/v1/admin/updates/" + value.getId())).body(ApiResponse.of(UpdateResponse.ManagementDetail.of(value)));
        });
    }
    @PutMapping(value="/{id:[0-9]+}", consumes="application/json")
    public ApiResponse<?> replace(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody(required=false) String raw,
            @RequestParam MultiValueMap<String,String> query, HttpServletRequest request) {
        long actor = actor(jwt), target = UpdateRequest.id(id);
        return audited("REPLACE", actor, target, request, () -> {
            UpdateRequest.query(query, Set.of());
            var body = UpdateRequest.parse(mapper, raw, REPLACE);
            return ApiResponse.of(UpdateResponse.ManagementDetail.of(updates.replace(actor, target, UpdateRequest.revision(body), UpdateRequest.input(body))));
        });
    }
    @DeleteMapping(value="/{id:[0-9]+}", consumes="application/json")
    public ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody(required=false) String raw,
            @RequestParam MultiValueMap<String,String> query, HttpServletRequest request) {
        long actor = actor(jwt), target = UpdateRequest.id(id);
        return audited("DELETE", actor, target, request, () -> {
            UpdateRequest.query(query, Set.of());
            var body = UpdateRequest.parse(mapper, raw, Set.of("expectedRevision"));
            updates.delete(actor, target, UpdateRequest.revision(body));
            return ResponseEntity.noContent().build();
        });
    }
    private <T> T audited(String action, long actor, Long target, HttpServletRequest request, Supplier<T> work) {
        try { T result = work.get(); audit.record("ADMIN_UPDATE_" + action, "SUCCESS", "serviceUpdate", actor, target, request); return result; }
        catch (ApplicationException e) { audit.record("ADMIN_UPDATE_" + action, e.error().code(), "serviceUpdate", actor, target, request); throw e; }
        catch (RuntimeException e) { audit.record("ADMIN_UPDATE_" + action, "BACKEND_FAILURE", "serviceUpdate", actor, target, request); throw e; }
    }
    private long actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || !jwt.getSubject().matches("admin:[1-9][0-9]*"))
            throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
        try { return Long.parseLong(jwt.getSubject().substring(6)); }
        catch (NumberFormatException e) { throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN); }
    }
}
