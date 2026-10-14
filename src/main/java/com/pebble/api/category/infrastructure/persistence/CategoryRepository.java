package com.pebble.api.category.infrastructure.persistence;

import com.pebble.api.category.domain.Category;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByDisplayOrderAscIdAsc();
    List<Category> findByParentIdOrderByDisplayOrderAscIdAsc(Long parentId);
    boolean existsByParentId(Long parentId);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);

    // 생성·이동을 같은 트랜잭션 잠금으로 직렬화해 비어 있는 트리의 경합도 막는다.
    @Query(value = "select 1 from pg_advisory_xact_lock(1885692465, 1)", nativeQuery = true)
    int lockManagementStructure();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Category c where c.id=:id")
    Optional<Category> findForManagementUpdate(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Category c where c.id=:id")
    Optional<Category> findForSelection(@Param("id") long id);
}
