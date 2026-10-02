package com.pebble.api.post.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostQueryService {
    private final PostRepository posts;

    @Transactional
    public com.pebble.api.post.domain.Post findForComments(long id, boolean forWrite) {
        var post = (forWrite ? posts.findByIdForUpdate(id) : posts.findById(id)).orElseThrow(PostQueryService::notFound);
        post.getAuthor().getStatus();
        return post;
    }

    @Transactional
    public void requirePublicForWrite(long id) {
        var post = posts.findByIdForUpdate(id).orElseThrow(PostQueryService::notFound);
        if (post.getVisibility() != PostVisibility.PUBLIC || post.isBlocked()
                || post.getAuthor().getStatus() == MemberStatus.WITHDRAWAL_PENDING) throw notFound();
    }

    private static ApplicationException notFound() {
        return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
    }
}
