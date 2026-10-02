package com.pebble.api.post.infrastructure.persistence;

import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostVisibility;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long>, JpaSpecificationExecutor<Post> {
    List<Post> findByAuthorIdAndVisibilityNotOrderByDisplayOrderAscIdAsc(Long authorId, PostVisibility visibility);

    @Override
    @EntityGraph(attributePaths = {"author", "category"})
    Optional<Post> findById(Long id);

    @EntityGraph(attributePaths = {"author", "category"})
    Optional<Post> findByAuthorHandleAndSlug(String handle, String slug);

    @EntityGraph(attributePaths = {"author", "category"})
    Optional<Post> findByAuthorHandleAndPostNumber(String handle, long postNumber);

    @Query("select coalesce(max(p.postNumber), 0) from Post p where p.author.id = :authorId")
    long findLastNumber(@Param("authorId") long authorId);

    boolean existsByAuthorIdAndSlug(Long authorId, String slug);

    @Override
    @EntityGraph(attributePaths = {"author", "category"})
    Page<Post> findAll(Specification<Post> specification, Pageable pageable);

    @Query(value = "select distinct p.category_id from post p join member m on m.id = p.author_member_id "
            + "where p.visibility_status = 'PUBLIC' and not p.is_blocked "
            + "and m.status <> 'WITHDRAWAL_PENDING' and p.category_id is not null", nativeQuery = true)
    List<Long> findPublicCategoryIds();

    @Query(value = "select distinct pt.tag_id from post_tag pt join post p on p.id = pt.post_id "
            + "join member m on m.id = p.author_member_id where p.visibility_status = 'PUBLIC' "
            + "and not p.is_blocked and m.status <> 'WITHDRAWAL_PENDING'", nativeQuery = true)
    List<Long> findPublicTagIds();

    @Modifying(flushAutomatically = true)
    @Query("update Post p set p.boardId = null, p.updatedAt = :now where p.author.id = :memberId and p.boardId = :boardId")
    int detachBoard(@Param("memberId") long memberId, @Param("boardId") long boardId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update Post p set p.projectId = null, p.updatedAt = :now where p.author.id = :memberId and p.projectId = :projectId")
    int detachProject(@Param("memberId") long memberId, @Param("projectId") long projectId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id")
    Optional<Post> findByIdForUpdate(@Param("id") Long id);

    @Query("select p.author.id from Post p where p.id = :id")
    Optional<Long> findAuthorIdForManagement(@Param("id") long id);
}
