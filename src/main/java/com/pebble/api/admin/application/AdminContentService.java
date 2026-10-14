package com.pebble.api.admin.application;

import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.like.application.PostLikeService;
import com.pebble.api.like.application.ProjectLikeService;
import com.pebble.api.post.application.PostService;
import com.pebble.api.post.application.PostService.PostView;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.project.application.ProjectService;
import com.pebble.api.project.application.ProjectService.ProjectView;
import com.pebble.api.project.domain.ProjectVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminContentService {
    private final AdminAccountRepository accounts;
    private final PostService posts;
    private final ProjectService projects;
    private final PostLikeService postLikes;
    private final ProjectLikeService projectLikes;

    public Page<PostView> listPosts(long actorId, String q, PostVisibility visibility, Boolean blocked, Long authorId, Pageable pageable) {
        requireOperator(actorId);
        return postLikes.decorate(posts.listForManagement(q, visibility, blocked, authorId, pageable), null);
    }

    public Page<ProjectView> listProjects(long actorId, String q, ProjectVisibility visibility, Boolean blocked, Long ownerId, Pageable pageable) {
        requireOperator(actorId);
        return projectLikes.decorate(projects.listForManagement(q, visibility, blocked, ownerId, pageable), null);
    }

    public PostView detailPost(long actorId, long id) {
        requireOperator(actorId);
        return postLikes.decorate(posts.detailForManagement(id), null);
    }

    public ProjectView detailProject(long actorId, long id) {
        requireOperator(actorId);
        return projectLikes.decorate(projects.detailForManagement(id), null);
    }

    public PostView blockPost(long actorId, long id, boolean blocked) {
        requireOperator(actorId);
        return postLikes.decorate(posts.setBlockedForManagement(id, actorId, blocked), null);
    }

    public ProjectView blockProject(long actorId, long id, boolean blocked) {
        requireOperator(actorId);
        return projectLikes.decorate(projects.setBlockedForManagement(id, actorId, blocked), null);
    }

    public void deletePost(long actorId, long id) {
        requireOperator(actorId);
        posts.deleteForManagement(id);
    }

    public void deleteProject(long actorId, long id) {
        requireOperator(actorId);
        projects.deleteForManagement(id);
    }

    private void requireOperator(long actorId) {
        var actor = accounts.findForAuthenticationById(actorId)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.INVALID_TOKEN));
        if (actor.getStatus() != AdminStatus.ACTIVE) throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
    }
}
