package com.pebble.api.like.application;

import com.pebble.api.like.domain.LikeSummary;
import com.pebble.api.like.infrastructure.persistence.ProjectLikeRepository;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.project.application.ProjectQueryService;
import com.pebble.api.project.application.ProjectService.ProjectView;
import com.pebble.api.project.domain.Project;
import com.pebble.api.project.domain.ProjectVisibility;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectLikeService {
    private final ProjectLikeRepository likes;
    private final MemberQueryService members;
    private final ProjectQueryService contents;

    @Transactional
    public void register(long contentId, long memberId) {
        members.findActiveForWrite(memberId);
        contents.requirePublicForWrite(contentId);
        likes.register(contentId, memberId);
    }

    @Transactional
    public void cancel(long contentId, long memberId) {
        members.findActiveForWrite(memberId);
        contents.requirePublicForWrite(contentId);
        likes.cancel(contentId, memberId);
    }

    public ProjectView decorate(ProjectView view, Long requesterId) {
        LikeSummary summary = isPublic(view.project())
                ? likes.summaries(List.of(view.project().getId()), requesterId).getOrDefault(view.project().getId(), LikeSummary.EMPTY)
                : LikeSummary.EMPTY;
        return withSummary(view, summary);
    }

    public Page<ProjectView> decorate(Page<ProjectView> page, Long requesterId) {
        var ids = page.getContent().stream().filter(view -> isPublic(view.project())).map(view -> view.project().getId()).toList();
        var summaries = likes.summaries(ids, requesterId);
        return page.map(view -> withSummary(view, summaries.getOrDefault(view.project().getId(), LikeSummary.EMPTY)));
    }

    private ProjectView withSummary(ProjectView view, LikeSummary summary) {
        return new ProjectView(view.project(), view.features(), view.links(), view.tags(), view.detail(), view.owner(), summary.count(), summary.likedByMe());
    }

    private boolean isPublic(Project content) {
        return content.getVisibility() == ProjectVisibility.PUBLIC && !content.isBlocked()
                && content.getOwner().getStatus() != MemberStatus.WITHDRAWAL_PENDING;
    }
}

