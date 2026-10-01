package com.pebble.api.post.infrastructure.persistence;

import com.pebble.api.post.domain.PostBlock;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostBlockRepository extends JpaRepository<PostBlock, Long> {
    List<PostBlock> findByPostIdOrderByDisplayOrderAsc(Long postId);

    @Modifying(flushAutomatically = true)
    @Query("delete from PostBlock b where b.post.id = :postId")
    void deleteForPost(@Param("postId") long postId);
}
