package com.pebble.api.update.presentation;
import com.pebble.api.update.domain.*;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public final class UpdateResponse {
    private UpdateResponse() {}
    public record Summary(String id, String title, String summary, UpdateType type, String version, Instant publishedAt, Instant updatedAt) {
        public static Summary of(ServiceUpdate u) { return new Summary(u.getId().toString(), u.getTitle(), u.getSummary(), u.getType(), u.getVersion(), u.getPublishedAt(), u.getUpdatedAt()); }
    }
    public record Detail(String id, String title, String summary, String body, UpdateType type, String version, Instant publishedAt, Instant updatedAt) {
        public static Detail of(ServiceUpdate u) { return new Detail(u.getId().toString(), u.getTitle(), u.getSummary(), u.getBody(), u.getType(), u.getVersion(), u.getPublishedAt(), u.getUpdatedAt()); }
    }
    public record ManagementSummary(String id, String title, String summary, UpdateType type, String version, UpdateStatus status, long revision, Instant publishedAt, Instant updatedAt) {
        public static ManagementSummary of(ServiceUpdate u) { return new ManagementSummary(u.getId().toString(), u.getTitle(), u.getSummary(), u.getType(), u.getVersion(), u.getStatus(), u.getRevision(), u.getPublishedAt(), u.getUpdatedAt()); }
    }
    public record ManagementDetail(String id, String title, String summary, String body, UpdateType type, String version, UpdateStatus status, long revision, Instant publishedAt, Instant updatedAt) {
        public static ManagementDetail of(ServiceUpdate u) { return new ManagementDetail(u.getId().toString(), u.getTitle(), u.getSummary(), u.getBody(), u.getType(), u.getVersion(), u.getStatus(), u.getRevision(), u.getPublishedAt(), u.getUpdatedAt()); }
    }
    public record Listing<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        public static <T> Listing<T> of(Page<?> page, List<T> items) { return new Listing<>(items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()); }
    }
}
