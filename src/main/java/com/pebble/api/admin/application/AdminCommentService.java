package com.pebble.api.admin.application;

import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.comment.application.CommentService;
import com.pebble.api.comment.domain.Comment;
import com.pebble.api.comment.domain.CommentTarget;
import com.pebble.api.comment.domain.CommentVisibility;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminCommentService {
    private final AdminAccountRepository accounts;
    private final CommentService comments;

    public Page<Comment> list(long actorId, CommentTarget type, Long contentId, Long authorId,
            CommentVisibility visibility, Boolean deleted, Pageable pageable) {
        requireOperator(actorId);
        return comments.listForManagement(type, contentId, authorId, visibility, deleted, pageable);
    }

    public void delete(long actorId, CommentTarget type, long commentId) {
        requireOperator(actorId);
        comments.deleteForManagement(type, commentId);
    }

    private void requireOperator(long actorId) {
        var actor = accounts.findForAuthenticationById(actorId)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.INVALID_TOKEN));
        if (actor.getStatus() != AdminStatus.ACTIVE) throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
    }
}
