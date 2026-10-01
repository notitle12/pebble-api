package com.pebble.api.category.infrastructure.persistence;

import com.pebble.api.category.domain.Category;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByDisplayOrderAscIdAsc();
    boolean existsByParentId(Long parentId);
}
