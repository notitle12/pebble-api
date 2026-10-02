package com.pebble.api.comment.application;

import com.pebble.api.comment.domain.*;
import com.pebble.api.comment.infrastructure.persistence.PostCommentRepository;
import com.pebble.api.comment.infrastructure.persistence.ProjectCommentRepository;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.post.application.PostQueryService;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.project.application.ProjectQueryService;
import com.pebble.api.project.domain.ProjectVisibility;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {
    private final PostCommentRepository postComments;
    private final ProjectCommentRepository projectComments;
    private final PostQueryService posts;
    private final ProjectQueryService projects;
    private final MemberQueryService members;

    @Transactional
    public Comment create(CommentTarget type, long contentId, long memberId, CommentChanges input) {
        var author = members.findActiveForWrite(memberId);
        Content content = content(type, contentId, true);
        if (!content.publicVisible()) throw notFound();
        return type == CommentTarget.POST
                ? postComments.saveAndFlush(new PostComment(contentId, author, input.body(), input.visibility()))
                : projectComments.saveAndFlush(new ProjectComment(contentId, author, input.body(), input.visibility()));
    }

    public Page<Comment> list(CommentTarget type, long contentId, Long requesterId, Pageable pageable) {
        Content content = content(type, contentId, false);
        boolean owner = Objects.equals(content.ownerId(), requesterId);
        if (!content.publicVisible()) {
            if (!owner) throw notFound();
            members.findActiveById(requesterId);
        }
        Long privateReader = requesterId != null && members.canReadPrivateInteractions(requesterId) ? requesterId : null;
        boolean ownerReader = owner && privateReader != null;
        return type == CommentTarget.POST
                ? postComments.findVisible(contentId, privateReader, ownerReader, pageable).map(comment -> (Comment) comment)
                : projectComments.findVisible(contentId, privateReader, ownerReader, pageable).map(comment -> (Comment) comment);
    }

    public Comment detail(CommentTarget type, long contentId, long commentId, Long requesterId) {
        Content content = content(type, contentId, false);
        Comment comment = find(type, contentId, commentId, false);
        boolean owner = Objects.equals(content.ownerId(), requesterId);
        if (comment.getVisibility() == CommentVisibility.SECRET) {
            if (!owner && !Objects.equals(comment.getAuthor().getId(), requesterId)) throw notFound();
            members.findActiveById(requesterId);
        } else if (!content.publicVisible()) {
            if (!owner) throw notFound();
            members.findActiveById(requesterId);
        }
        return comment;
    }

    @Transactional
    public Comment update(CommentTarget type, long contentId, long commentId, long memberId, CommentChanges input) {
        members.findActiveForWrite(memberId);
        content(type, contentId, true);
        Comment comment = find(type, contentId, commentId, true);
        if (!comment.getAuthor().getId().equals(memberId)) throw notFound();
        comment.update(input.hasBody() ? input.body() : comment.getBody(),
                input.hasVisibility() ? input.visibility() : comment.getVisibility());
        flush(type);
        return comment;
    }

    @Transactional
    public void delete(CommentTarget type, long contentId, long commentId, long memberId) {
        members.findActiveForWrite(memberId);
        content(type, contentId, true);
        Comment comment = find(type, contentId, commentId, true);
        if (!comment.getAuthor().getId().equals(memberId)) throw notFound();
        comment.delete();
        flush(type);
    }

    private Comment find(CommentTarget type, long contentId, long commentId, boolean forWrite) {
        Comment comment = type == CommentTarget.POST
                ? (forWrite ? postComments.findForUpdate(commentId) : postComments.findById(commentId)).orElseThrow(CommentService::notFound)
                : (forWrite ? projectComments.findForUpdate(commentId) : projectComments.findById(commentId)).orElseThrow(CommentService::notFound);
        if (comment.getContentId() != contentId || comment.getDeletedAt() != null
                || comment.getAuthor().getStatus() == MemberStatus.WITHDRAWAL_PENDING) throw notFound();
        return comment;
    }

    private Content content(CommentTarget type, long id, boolean forWrite) {
        if (type == CommentTarget.POST) {
            var post = posts.findForComments(id, forWrite);
            if (post.getVisibility() == PostVisibility.DELETED || post.getAuthor().getStatus() == MemberStatus.WITHDRAWAL_PENDING) throw notFound();
            return new Content(post.getAuthor().getId(), post.getVisibility() == PostVisibility.PUBLIC && !post.isBlocked());
        }
        var project = projects.findForComments(id, forWrite);
        if (project.getVisibility() == ProjectVisibility.DELETED || project.getOwner().getStatus() == MemberStatus.WITHDRAWAL_PENDING) throw notFound();
        return new Content(project.getOwner().getId(), project.getVisibility() == ProjectVisibility.PUBLIC && !project.isBlocked());
    }
    private void flush(CommentTarget type) {
        if (type == CommentTarget.POST) postComments.flush(); else projectComments.flush();
    }
    private static ApplicationException notFound() { return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND); }
    private record Content(Long ownerId, boolean publicVisible) { }
}
