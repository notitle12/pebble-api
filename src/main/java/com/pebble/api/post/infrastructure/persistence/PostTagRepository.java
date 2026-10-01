package com.pebble.api.post.infrastructure.persistence;

import com.pebble.api.post.domain.PostTag;
import com.pebble.api.post.domain.PostTagId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostTagRepository extends JpaRepository<PostTag, PostTagId> {
    @Query("select pt from PostTag pt join fetch pt.tag t where pt.post.id in :postIds "
            + "order by t.displayOrder, t.id")
    List<PostTag> findForPosts(@Param("postIds") Collection<Long> postIds);
}
