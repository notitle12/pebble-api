package com.pebble.api.post.application;

import com.pebble.api.global.exception.*;
import com.pebble.api.global.media.*;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.post.domain.*;
import com.pebble.api.post.infrastructure.persistence.*;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class PostBodyImageService {
    private final MemberQueryService members;
    private final PostRepository posts;
    private final PostBodyImageRepository images;
    private final PostBlockRepository blocks;
    private final ImageProcessor processor;
    private final ObjectProvider<R2ObjectStorage> storage;
    private final MediaDeletionQueue deletion;

    @Transactional
    public ImageView upload(long memberId,long postId,byte[] source) {
        Post post=owned(memberId,postId,true);
        if(images.countByPostId(postId)>=50) throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR,"file","본문 이미지는 글당 최대 50개입니다.");
        var client=client();
        var processed=processor.process(source);
        String key="post/"+UUID.randomUUID()+"/body.webp";
        var keys=List.of(key);
        deletion.stage(keys); deletion.lockStaged(keys);
        client.put(key,processed.display());
        var image=images.saveAndFlush(new PostBodyImage(post,key));
        deletion.retain(keys);
        return view(image,client);
    }

    public List<ImageView> list(long memberId,long postId) {
        owned(memberId,postId,false);
        var rows=images.findByPostIdOrderByIdAsc(postId);
        if(rows.isEmpty())return List.of();
        var client=client();
        return rows.stream().map(image->view(image,client)).toList();
    }

    @Transactional
    public void delete(long memberId,long postId,long imageId) {
        Post post=owned(memberId,postId,true);
        PostBodyImage image=images.findByIdAndPostId(imageId,postId).orElseThrow(PostBodyImageService::notFound);
        if(java.util.Objects.equals(post.getThumbnailImageId(),imageId)) {
            post.changeThumbnailImageId(null);
            post.changeThumbnail(null);
            posts.flush();
        }
        images.delete(image);
        images.flush();
    }

    public String publicUrl(long postId,long imageId) {
        Post post=posts.findById(postId).orElseThrow(PostBodyImageService::notFound);
        if(post.getVisibility()!=PostVisibility.PUBLIC || post.isBlocked() || post.isDraft()
                ||post.getAuthor().getStatus()==MemberStatus.WITHDRAWAL_PENDING) throw notFound();
        var image=images.findByIdAndPostId(imageId,postId).orElseThrow(PostBodyImageService::notFound);
        String reference="/api/v1/posts/"+postId+"/images/"+imageId+"/content";
        // 공개 글 수정 중 새로 업로드한 파일도 실제 발행 본문에 연결되기 전에는 노출하지 않는다.
        boolean referenced=blocks.findByPostIdOrderByDisplayOrderAsc(postId).stream()
                .filter(block->block.getType()==BlockType.HTML || block.getType()==BlockType.MARKDOWN)
                .anyMatch(block->block.getContent().contains(reference));
        if(!referenced)throw notFound();
        return client().signedUrl(image.getStorageKey());
    }

    private Post owned(long memberId,long postId,boolean lock) {
        if(lock)members.findProfileCompletedForWrite(memberId); else members.findActiveById(memberId);
        var post=(lock?posts.findByIdForUpdate(postId):posts.findById(postId)).orElseThrow(PostBodyImageService::notFound);
        if(!post.getAuthor().getId().equals(memberId))throw notFound();
        if(post.getVisibility()==PostVisibility.DELETED)throw new ApplicationException(PostError.CONTENT_DELETED);
        return post;
    }
    private R2ObjectStorage client() {
        var value=storage.getIfAvailable();
        if(value==null)throw new ApplicationException(GlobalErrorCode.STORAGE_UNAVAILABLE);
        return value;
    }
    private ImageView view(PostBodyImage image,R2ObjectStorage client) {return new ImageView(image.getId().toString(),client.signedUrl(image.getStorageKey()),null);}
    private static ApplicationException notFound(){return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);}
    public record ImageView(String id,String url,String altText) { }
}
