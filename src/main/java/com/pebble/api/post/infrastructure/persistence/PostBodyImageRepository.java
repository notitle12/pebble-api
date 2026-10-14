package com.pebble.api.post.infrastructure.persistence;

import com.pebble.api.post.domain.PostBodyImage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostBodyImageRepository extends JpaRepository<PostBodyImage,Long> {
    List<PostBodyImage> findByPostIdOrderByIdAsc(Long postId);
    Optional<PostBodyImage> findByIdAndPostId(Long id, Long postId);
    long countByPostId(Long postId);
}
