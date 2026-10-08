package com.pebble.api.post.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.media.ImageProcessor;
import com.pebble.api.global.media.MediaDeletionQueue;
import com.pebble.api.global.media.R2ObjectStorage;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.post.domain.PostError;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class PostMediaService {
    private final MemberQueryService members;
    private final PostRepository posts;
    private final ImageProcessor images;
    private final ObjectProvider<R2ObjectStorage> storage;
    private final MediaDeletionQueue deletion;

    public String upload(long memberId, long postId, byte[] source) {
        Post post = owned(memberId, postId);
        var client = client();
        var processed = images.process(source);
        String key = "post/" + UUID.randomUUID() + "/thumbnail.webp";
        var keys = List.of(key);
        deletion.stage(keys);
        deletion.lockStaged(keys);
        client.put(key, processed.thumbnail());
        post.changeThumbnailImageId(null);
        post.changeThumbnail(key);
        posts.flush();
        deletion.retain(keys);
        return post.getVisibility() == PostVisibility.PUBLIC && !post.isBlocked() ? client.signedUrl(key) : null;
    }

    public void delete(long memberId, long postId) {
        Post post = owned(memberId, postId);
        post.changeThumbnailImageId(null);
        post.changeThumbnail(null);
        posts.flush();
    }

    private Post owned(long memberId, long postId) {
        members.findProfileCompletedForWrite(memberId);
        Post post = posts.findByIdForUpdate(postId).orElseThrow(PostMediaService::notFound);
        if (!post.getAuthor().getId().equals(memberId)) throw notFound();
        if (post.getVisibility() == PostVisibility.DELETED) {
            throw new ApplicationException(PostError.CONTENT_DELETED);
        }
        return post;
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
