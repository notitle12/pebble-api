package com.pebble.api.like.application;

import com.pebble.api.like.domain.LikeSummary;
import com.pebble.api.like.infrastructure.persistence.PostLikeRepository;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.post.application.PostQueryService;
import com.pebble.api.post.application.PostService.PostView;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostVisibility;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostLikeService {
    private final PostLikeRepository likes;
    private final MemberQueryService members;
    private final PostQueryService contents;

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

    public PostView decorate(PostView view, Long requesterId) {
        LikeSummary summary = isPublic(view.post())
                ? likes.summaries(List.of(view.post().getId()), requesterId).getOrDefault(view.post().getId(), LikeSummary.EMPTY)
                : LikeSummary.EMPTY;
        return withSummary(view, summary);
    }

    public Page<PostView> decorate(Page<PostView> page, Long requesterId) {
        var ids = page.getContent().stream().filter(view -> isPublic(view.post())).map(view -> view.post().getId()).toList();
        var summaries = likes.summaries(ids, requesterId);
        return page.map(view -> withSummary(view, summaries.getOrDefault(view.post().getId(), LikeSummary.EMPTY)));
    }

    private PostView withSummary(PostView view, LikeSummary summary) {
        return new PostView(view.post(), view.blocks(), view.tags(), view.owner(), summary.count(), summary.likedByMe());
    }

    private boolean isPublic(Post content) {
        return content.getVisibility() == PostVisibility.PUBLIC && !content.isBlocked()
                && content.getAuthor().getStatus() != MemberStatus.WITHDRAWAL_PENDING;
    }
}

