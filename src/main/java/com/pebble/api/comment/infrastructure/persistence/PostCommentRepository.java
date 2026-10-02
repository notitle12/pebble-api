package com.pebble.api.comment.infrastructure.persistence;

import com.pebble.api.comment.domain.PostComment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {
    @Override
    @EntityGraph(attributePaths = "author")
    Optional<PostComment> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PostComment c join fetch c.author where c.id=:id")
    Optional<PostComment> findForUpdate(@Param("id") long id);

    @EntityGraph(attributePaths = "author")
    @Query("""
            select c from PostComment c
            where c.contentId=:contentId and c.deletedAt is null
              and c.author.status <> com.pebble.api.member.domain.MemberStatus.WITHDRAWAL_PENDING
              and (c.visibility = com.pebble.api.comment.domain.CommentVisibility.PUBLIC
                   or c.author.id=:requesterId or :owner=true)
            """)
    Page<PostComment> findVisible(@Param("contentId") long contentId, @Param("requesterId") Long requesterId,
                                  @Param("owner") boolean owner, Pageable pageable);
}
