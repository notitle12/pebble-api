package com.pebble.api.category.application;

import com.pebble.api.category.domain.Category;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.category.infrastructure.persistence.CategoryRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryRepository categories;

    public List<CategoryBranch> findPublicTree() {
        List<Category> ordered = categories.findAllByOrderByDisplayOrderAscIdAsc();
        Map<Long, List<Category>> children = new HashMap<>();
        for (Category category : ordered) {
            if (category.getParent() != null && category.getStatus() == CategoryStatus.ACTIVE) {
                children.computeIfAbsent(category.getParent().getId(), key -> new ArrayList<>()).add(category);
            }
        }
        List<CategoryBranch> roots = new ArrayList<>();
        for (Category category : ordered) {
            if (category.getParent() == null) {
                List<Category> visibleChildren = children.getOrDefault(category.getId(), List.of());
                // 활성 하위의 경로를 보존하되 비활성 상위를 선택 가능한 분류로 바꾸지 않는다.
                if (category.getStatus() == CategoryStatus.ACTIVE || !visibleChildren.isEmpty()) {
                    roots.add(new CategoryBranch(category, List.copyOf(visibleChildren)));
                }
            }
        }
        return List.copyOf(roots);
    }

    public record CategoryBranch(Category category, List<Category> children) {
    }
}
