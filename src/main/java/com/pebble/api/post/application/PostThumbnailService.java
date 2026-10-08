package com.pebble.api.post.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.media.ImageProcessor;
import com.pebble.api.global.media.MediaDeletionQueue;
import com.pebble.api.global.media.R2ObjectStorage;
import com.pebble.api.post.domain.BlockType;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostBodyImage;
import com.pebble.api.post.domain.PostBlock;
import com.pebble.api.post.infrastructure.persistence.PostBodyImageRepository;
import com.pebble.api.post.infrastructure.persistence.PostBlockRepository;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Post 저장 트랜잭션에서 본문 이미지 선택과 목록용 썸네일 파생물을 함께 반영한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class PostThumbnailService {
    private static final int MAX_BODY_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Pattern HTML_NON_RENDERED = Pattern.compile("(?is)<!--.*?-->|<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern HTML_IMAGE_SOURCE = Pattern.compile("(?is)<img\\b[^>]*?[\\s]src\\s*=\\s*(['\"])([^'\"]+)\\1");
    private static final Pattern MARKDOWN_IMAGE_SOURCE = Pattern.compile("!\\[[^]]*]\\(\\s*<?([^\\s)>]+)", Pattern.DOTALL);
    private final PostRepository posts;
    private final PostBodyImageRepository images;
    private final PostBlockRepository blocks;
    private final ImageProcessor processor;
    private final ObjectProvider<R2ObjectStorage> storage;
    private final MediaDeletionQueue deletion;

    public void select(Post post, Long imageId) {
        if (imageId == null) {
            clear(post);
            return;
        }

        PostBodyImage image = images.findByIdAndPostId(imageId, post.getId()).orElseThrow(PostThumbnailService::notFound);
        if (!isReferenced(post, imageId)) {
            throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, "thumbnailImageId",
                    "대표 이미지는 현재 본문에 포함된 이미지여야 합니다.");
        }
        if (Objects.equals(post.getThumbnailImageId(), imageId) && post.getThumbnailStorageKey() != null) return;

        R2ObjectStorage client = client();
        byte[] savedBodyImage = client.get(image.getStorageKey(), MAX_BODY_IMAGE_BYTES);
        byte[] thumbnail = processor.process(savedBodyImage).thumbnail();
        String newKey = "post/" + UUID.randomUUID() + "/thumbnail.webp";
        List<String> keys = List.of(newKey);
        deletion.stage(keys);
        deletion.lockStaged(keys);
        client.put(newKey, thumbnail);
        post.changeThumbnailImageId(imageId);
        post.changeThumbnail(newKey);
        posts.flush();
        deletion.retain(keys);
    }

    /** 본문 전체 교체로 선택한 이미지가 빠지면 대표 선택과 파생본도 함께 비운다. */
    public void clearIfNoLongerReferenced(Post post) {
        Long selectedImageId = post.getThumbnailImageId();
        if (selectedImageId != null && !isReferenced(post, selectedImageId)) clear(post);
    }

    private boolean isReferenced(Post post, long imageId) {
        String reference = "/api/v1/posts/" + post.getId() + "/images/" + imageId + "/content";
        return blocks.findByPostIdOrderByDisplayOrderAsc(post.getId()).stream()
                .filter(block -> block.getType() == BlockType.HTML || block.getType() == BlockType.MARKDOWN)
                .anyMatch(block -> containsImageReference(block, reference));
    }

    private boolean containsImageReference(PostBlock block, String expectedPath) {
        Pattern pattern = block.getType() == BlockType.HTML ? HTML_IMAGE_SOURCE : MARKDOWN_IMAGE_SOURCE;
        String content = block.getType() == BlockType.HTML
                ? HTML_NON_RENDERED.matcher(block.getContent()).replaceAll("")
                : block.getContent();
        Matcher matcher = pattern.matcher(content);
        int sourceGroup = block.getType() == BlockType.HTML ? 2 : 1;
        while (matcher.find()) {
            try {
                if (expectedPath.equals(URI.create(matcher.group(sourceGroup)).getPath())) return true;
            } catch (IllegalArgumentException ignored) {
                // 유효하지 않은 이미지 URL은 대표 이미지 참조로 보지 않는다.
            }
        }
        return false;
    }

    private void clear(Post post) {
        boolean changed = post.getThumbnailImageId() != null || post.getThumbnailStorageKey() != null;
        if (!changed) return;
        post.changeThumbnailImageId(null);
        post.changeThumbnail(null);
        posts.flush();
    }

    private R2ObjectStorage client() {
        R2ObjectStorage client = storage.getIfAvailable();
        if (client == null) throw new ApplicationException(GlobalErrorCode.STORAGE_UNAVAILABLE);
        return client;
    }

    private static ApplicationException notFound() {
        return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
    }
}
