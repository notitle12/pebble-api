package com.pebble.api.tag.infrastructure.persistence;

import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByStatusOrderByDisplayOrderAscIdAsc(TagStatus status);
    List<Tag> findAllByOrderByDisplayOrderAscIdAsc();
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tag t where t.id=:id")
    Optional<Tag> findForManagementUpdate(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from Tag t where t.id in :ids order by t.id")
    List<Tag> findForSelection(@Param("ids") List<Long> ids);
}
