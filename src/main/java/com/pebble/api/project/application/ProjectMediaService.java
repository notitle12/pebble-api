package com.pebble.api.project.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.media.ImageProcessor;
import com.pebble.api.global.media.MediaDeletionQueue;
import com.pebble.api.global.media.R2ObjectStorage;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.project.domain.MediaRole;
import com.pebble.api.project.domain.Project;
import com.pebble.api.project.domain.ProjectError;
import com.pebble.api.project.domain.ProjectMedia;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.project.infrastructure.persistence.ProjectMediaRepository;
import com.pebble.api.project.infrastructure.persistence.ProjectRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectMediaService {
    private final MemberQueryService members;
    private final ProjectRepository projects;
    private final ProjectMediaRepository media;
    private final ImageProcessor images;
    private final ObjectProvider<R2ObjectStorage> storage;
    private final MediaDeletionQueue deletion;

    public ProjectMediaView upload(
            long memberId, long projectId, byte[] source, MediaRole role, String altText, int order) {
        Project project = owned(memberId, projectId);
        if (role == MediaRole.THUMBNAIL && media.existsByProjectIdAndMediaRole(projectId, role)) {
            throw new ApplicationException(ProjectError.THUMBNAIL_ALREADY_EXISTS);
        }
        var client = client();
        var processed = images.process(source);
        String base = "project/" + UUID.randomUUID();
        String key = base + "/display.webp";
        String thumbnail = base + "/thumbnail.webp";
        var keys = List.of(key, thumbnail);
        deletion.stage(keys);
        deletion.lockStaged(keys);
        client.put(key, processed.display());
        client.put(thumbnail, processed.thumbnail());
        ProjectMedia item = media.saveAndFlush(new ProjectMedia(projectId, role, key, thumbnail, altText, order));
        project.touchMedia();
        projects.flush();
        deletion.retain(keys);
        return view(item, project, client);
    }

    public ProjectMediaView update(
            long memberId, long projectId, long mediaId, boolean hasAlt, String altText, Integer order) {
        Project project = owned(memberId, projectId);
        ProjectMedia item = item(projectId, mediaId);
        String nextAlt = hasAlt ? altText : item.getAltText();
        int nextOrder = order == null ? item.getDisplayOrder() : order;
        boolean changed = !java.util.Objects.equals(nextAlt, item.getAltText())
                || nextOrder != item.getDisplayOrder();
        item.update(nextAlt, nextOrder);
        if (changed) project.touchMedia();
        media.flush();
        projects.flush();
        return view(item, project, storage.getIfAvailable());
    }

    public void delete(long memberId, long projectId, long mediaId) {
        Project project = owned(memberId, projectId);
        media.delete(item(projectId, mediaId));
        project.touchMedia();
        media.flush();
        projects.flush();
    }

    private Project owned(long memberId, long projectId) {
        members.findProfileCompletedForWrite(memberId);
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(ProjectMediaService::notFound);
        if (!project.getOwner().getId().equals(memberId)) throw notFound();
        if (project.getVisibility() == ProjectVisibility.DELETED) {
            throw new ApplicationException(ProjectError.CONTENT_DELETED);
        }
        return project;
    }

    private ProjectMedia item(long projectId, long mediaId) {
        return media.findById(mediaId)
                .filter(item -> item.getProjectId() == projectId)
                .orElseThrow(ProjectMediaService::notFound);
    }

    private ProjectMediaView view(ProjectMedia item, Project project, R2ObjectStorage client) {
        boolean visible = project.getVisibility() == ProjectVisibility.PUBLIC && !project.isBlocked() && client != null;
        return ProjectMediaView.from(
                item,
                visible ? client.signedUrl(item.getStorageKey()) : null,
                visible ? client.signedUrl(item.getThumbnailStorageKey()) : null);
    }

    private R2ObjectStorage client() {
        var client = storage.getIfAvailable();
        if (client == null) throw new ApplicationException(GlobalErrorCode.STORAGE_UNAVAILABLE);
        return client;
    }

    private static ApplicationException notFound() {
        return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
    }
}
