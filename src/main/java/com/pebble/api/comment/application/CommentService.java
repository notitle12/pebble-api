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
    private final GuestCommentSecurity guestSecurity;

    public Page<Comment> listForManagement(CommentTarget type, Long contentId, Long authorId,
            CommentVisibility visibility, Boolean deleted, Pageable pageable) {
        return type == CommentTarget.POST
                ? postComments.findForManagement(contentId, authorId, visibility, deleted, pageable).map(comment -> (Comment) comment)
                : projectComments.findForManagement(contentId, authorId, visibility, deleted, pageable).map(comment -> (Comment) comment);
    }

    @Transactional
    public void deleteForManagement(CommentTarget type, long commentId) {
        // 부모·회원 상태와 무관한 삭제는 댓글 행만 잠가 작성자 수정과 직렬화한다.
        Comment comment = type == CommentTarget.POST
                ? postComments.findForManagementUpdate(commentId).orElseThrow(CommentService::notFound)
                : projectComments.findForManagementUpdate(commentId).orElseThrow(CommentService::notFound);
        if (comment.getDeletedAt() != null) return;
        comment.delete();
        flush(type);
    }

    @Transactional
    public Comment create(CommentTarget type, long contentId, long memberId, CommentChanges input) {
        var author = members.findActiveForWrite(memberId);
        Content content = content(type, contentId, true);
        if (!content.publicVisible()) throw notFound();
        return type == CommentTarget.POST
                ? postComments.saveAndFlush(new PostComment(contentId, author, input.body(), input.visibility()))
                : projectComments.saveAndFlush(new ProjectComment(contentId, author, input.body(), input.visibility()));
    }

    public Page<CommentView> list(CommentTarget type, long contentId, Long requesterId, Pageable pageable) {
        Content content = content(type, contentId, false);
        boolean owner = Objects.equals(content.ownerId(), requesterId);
        if (!content.publicVisible()) {
            if (!owner) throw notFound();
            members.findActiveById(requesterId);
        }
        Long privateReader = requesterId != null && members.canReadPrivateInteractions(requesterId) ? requesterId : null;
        boolean ownerReader = owner && privateReader != null;
        return type == CommentTarget.POST
                ? postComments.findVisible(contentId, privateReader, ownerReader, pageable)
                        .map(comment -> visible(comment, ownerReader))
                : projectComments.findVisible(contentId, privateReader, ownerReader, pageable)
                        .map(comment -> visible(comment, ownerReader));
    }

    public CommentView detail(CommentTarget type, long contentId, long commentId, Long requesterId) {
        Content content = content(type, contentId, false);
        Comment comment = find(type, contentId, commentId, false);
        boolean owner = Objects.equals(content.ownerId(), requesterId);
        if (comment.getVisibility() == CommentVisibility.SECRET) {
            if (comment.isGuest()) {
                if (!owner) throw notFound();
                members.findActiveById(requesterId);
                return new CommentView(comment, false);
            }
            if (!owner && !Objects.equals(comment.getAuthor().getId(), requesterId)) throw notFound();
            members.findActiveById(requesterId);
        } else if (!content.publicVisible()) {
            if (!owner) throw notFound();
            members.findActiveById(requesterId);
        }
        return new CommentView(comment, false);
    }

    @Transactional
    public Comment update(CommentTarget type, long contentId, long commentId, long memberId, CommentChanges input) {
        members.findActiveForWrite(memberId);
        content(type, contentId, true);
        Comment comment = find(type, contentId, commentId, true);
        if (comment.isGuest() || !comment.getAuthor().getId().equals(memberId)) throw notFound();
        comment.update(input.hasBody() ? input.body() : comment.getBody(),
                input.hasVisibility() ? input.visibility() : comment.getVisibility());
        flush(type);
        return comment;
    }

    @Transactional
    public void delete(CommentTarget type, long contentId, long commentId, long memberId) {
        members.findActiveForWrite(memberId);
        Content content = content(type, contentId, true);
        Comment comment = find(type, contentId, commentId, true);
        if (comment.isGuest() ? !content.ownerId().equals(memberId) : !comment.getAuthor().getId().equals(memberId)) throw notFound();
        comment.delete();
        flush(type);
    }

    @Transactional
    public Comment createGuest(CommentTarget type,long contentId,String nickname,String password,CommentChanges changes) {
        Content content=content(type,contentId,true);
        if(!content.publicVisible())throw notFound();
        String hash=guestSecurity.encode(password);
        CommentVisibility visibility = changes.hasVisibility() ? changes.visibility() : CommentVisibility.PUBLIC;
        Comment comment=type==CommentTarget.POST
                ?postComments.saveAndFlush(new PostComment(contentId,nickname,hash,changes.body(),visibility))
                :projectComments.saveAndFlush(new ProjectComment(contentId,nickname,hash,changes.body(),visibility));
        return comment;
    }
    @Transactional
    public Comment updateGuest(CommentTarget type,long contentId,long commentId,String password,CommentChanges changes) {
        // 숨김 전 작성한 댓글도 올바른 비밀번호로 수정·삭제할 수 있으나 본문은 인증 전 반환하지 않는다.
        content(type,contentId,true);
        Comment comment=find(type,contentId,commentId,true);
        requireGuestPassword(comment,password);
        comment.update(changes.hasBody() ? changes.body() : comment.getBody(),
                changes.hasVisibility() ? changes.visibility() : comment.getVisibility());
        flush(type);
        return comment;
    }
    @Transactional
    public Comment readGuest(CommentTarget type,long contentId,long commentId,String password) {
        Content content=content(type,contentId,true);
        if(!content.publicVisible())throw notFound();
        Comment comment=find(type,contentId,commentId,true);
        requireGuestPassword(comment,password);
        return comment;
    }
    @Transactional
    public void deleteGuest(CommentTarget type,long contentId,long commentId,String password) {
        content(type,contentId,true);
        Comment comment=find(type,contentId,commentId,true);
        requireGuestPassword(comment,password);
        comment.delete();
        flush(type);
    }
    private void requireGuestPassword(Comment comment,String password) {
        if(!comment.isGuest())throw notFound();
        if(!guestSecurity.matches(password,comment.getGuestPasswordHash()))
            throw new ApplicationException(CommentError.INVALID_COMMENT_PASSWORD);
    }

    private CommentView visible(Comment comment, boolean ownerReader) {
        boolean locked = comment.isGuest() && comment.getVisibility() == CommentVisibility.SECRET && !ownerReader;
        return new CommentView(comment, locked);
    }

    private Comment find(CommentTarget type, long contentId, long commentId, boolean forWrite) {
        Comment comment = type == CommentTarget.POST
                ? (forWrite ? postComments.findForUpdate(commentId) : postComments.findById(commentId)).orElseThrow(CommentService::notFound)
                : (forWrite ? projectComments.findForUpdate(commentId) : projectComments.findById(commentId)).orElseThrow(CommentService::notFound);
        if (comment.getContentId() != contentId || comment.getDeletedAt() != null
                || (!comment.isGuest() && comment.getAuthor().getStatus() == MemberStatus.WITHDRAWAL_PENDING)) throw notFound();
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
