package com.pebble.api.project.application;

import com.pebble.api.project.infrastructure.persistence.ProjectTagRepository;
import com.pebble.api.project.infrastructure.persistence.ProjectRepository;
import com.pebble.api.project.domain.Project;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectQueryService {
    private final ProjectTagRepository projectTags;
    private final ProjectRepository projects;

    @Transactional
    public Project findForComments(long id, boolean forWrite) {
        Project project = (forWrite ? projects.findByIdForUpdate(id) : projects.findById(id)).orElseThrow(ProjectQueryService::notFound);
        project.getOwner().getStatus();
        return project;
    }

    @Transactional
    public void requirePublicForWrite(long projectId) {
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(ProjectQueryService::notFound);
        if (project.getVisibility() != ProjectVisibility.PUBLIC || project.isBlocked()
                || project.getOwner().getStatus() == MemberStatus.WITHDRAWAL_PENDING) throw notFound();
    }

    public void resolveForPost(long projectId, long ownerId) {
        Project project = projects.findById(projectId).orElseThrow(ProjectQueryService::notFound);
        if (!project.getOwner().getId().equals(ownerId) || project.getVisibility() == ProjectVisibility.DELETED) throw notFound();
    }

    public long requirePublic(long projectId) {
        Project project = projects.findById(projectId).orElseThrow(ProjectQueryService::notFound);
        if (project.getVisibility() != ProjectVisibility.PUBLIC || project.isBlocked()
                || project.getOwner().getStatus() == MemberStatus.WITHDRAWAL_PENDING) throw notFound();
        return project.getOwner().getId();
    }

    private static ApplicationException notFound() {
        return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
    }

    public List<Long> findPublicTagIds() {
        return projectTags.findPublicTagIds();
    }
}
