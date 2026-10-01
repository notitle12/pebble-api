package com.pebble.api.category.application;

import com.pebble.api.category.domain.Category;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.category.domain.CategoryError;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.category.infrastructure.persistence.CategoryRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryRepository categories;
    private final PostRepository posts;

    public Category resolveForPost(long id, Long existingId) {
        Category category = categories.findById(id)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
        // 기존 연결은 유지하며 새 연결의 최하위 여부는 비활성 자식까지 확인한다.
        if (!Long.valueOf(id).equals(existingId)
                && (category.getStatus() != CategoryStatus.ACTIVE || categories.existsByParentId(id))) {
            throw new ApplicationException(CategoryError.INVALID_CATEGORY_SELECTION);
        }
        return category;
    }

    public List<CategoryBranch> findPublicTree() {
        List<Category> ordered = categories.findAllByOrderByDisplayOrderAscIdAsc();
        Set<Long> used = new HashSet<>(posts.findPublicCategoryIds());
        Map<Long, List<Category>> children = new HashMap<>();
        for (Category category : ordered) {
            if (category.getParent() != null
                    && (category.getStatus() == CategoryStatus.ACTIVE || used.contains(category.getId()))) {
                children.computeIfAbsent(category.getParent().getId(), key -> new ArrayList<>()).add(category);
            }
        }
        List<CategoryBranch> roots = new ArrayList<>();
        for (Category category : ordered) {
            if (category.getParent() == null) {
                List<Category> visibleChildren = children.getOrDefault(category.getId(), List.of());
                // 활성 하위의 경로를 보존하되 비활성 상위를 선택 가능한 분류로 바꾸지 않는다.
                if (category.getStatus() == CategoryStatus.ACTIVE || used.contains(category.getId()) || !visibleChildren.isEmpty()) {
                    roots.add(new CategoryBranch(category, List.copyOf(visibleChildren)));
                }
            }
        }
        return List.copyOf(roots);
    }

    public record CategoryBranch(Category category, List<Category> children) {
    }
}
