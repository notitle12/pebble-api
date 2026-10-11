package com.pebble.api.techstack.infrastructure.persistence;

import com.pebble.api.techstack.domain.TechStack;
import com.pebble.api.techstack.domain.TechStackStatus;
import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TechStackRepository extends JpaRepository<TechStack, Long> {

    List<TechStack> findByStatusOrderByDisplayOrderAscIdAsc(TechStackStatus status);
    List<TechStack> findAllByOrderByDisplayOrderAscIdAsc();
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TechStack t where t.id=:id")
    Optional<TechStack> findForManagementUpdate(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from TechStack t where t.id in :ids order by t.id")
    List<TechStack> findForSelection(@Param("ids") List<Long> ids);
}
