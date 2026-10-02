package com.pebble.api.category.application;

import com.pebble.api.category.application.CategoryQueryService.CategoryBranch;
import com.pebble.api.category.domain.Category;
import com.pebble.api.category.domain.CategoryError;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.category.infrastructure.persistence.CategoryRepository;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {
    private final CategoryRepository categories;
    private final PostRepository posts;

    public CategoryBranch create(CategoryChanges input) {
        categories.lockManagementStructure();
        Category parent = parent(input.parentId());
        checkParent(null, parent);
        if (categories.existsBySlug(input.slug())) throw new ApplicationException(CategoryError.CATEGORY_SLUG_CONFLICT);
        Category created = categories.saveAndFlush(new Category(parent, input.name(), input.slug(), input.displayOrder(), CategoryStatus.ACTIVE));
        return branch(created);
    }

    public CategoryBranch update(long id, CategoryChanges input) {
        categories.lockManagementStructure();
        Category category = categories.findForManagementUpdate(id).orElseThrow(CategoryService::notFound);
        Category parent = input.has("parentId") ? parent(input.parentId()) : category.getParent();
        if (input.has("parentId")) {
            checkParent(category, parent);
            if (parent != null && categories.existsByParentId(id)) throw hierarchy();
        }
        String slug = input.has("slug") ? input.slug() : category.getSlug();
        if (categories.existsBySlugAndIdNot(slug, id)) throw new ApplicationException(CategoryError.CATEGORY_SLUG_CONFLICT);
        category.update(parent, input.has("name") ? input.name() : category.getName(), slug,
                input.has("displayOrder") ? input.displayOrder() : category.getDisplayOrder(),
                input.has("status") ? input.status() : category.getStatus());
        categories.flush();
        return branch(category);
    }

    private Category parent(Long id) {
        return id == null ? null : categories.findForManagementUpdate(id).orElseThrow(CategoryService::notFound);
    }

    private void checkParent(Category category, Category parent) {
        if (parent == null) return;
        if ((category != null && parent.getId().equals(category.getId())) || parent.getParent() != null
                || posts.existsByCategoryId(parent.getId())) throw hierarchy();
    }

    private CategoryBranch branch(Category category) {
        return new CategoryBranch(category, categories.findByParentIdOrderByDisplayOrderAscIdAsc(category.getId()));
    }
    private static ApplicationException hierarchy() { return new ApplicationException(CategoryError.CATEGORY_HIERARCHY_CONFLICT); }
    private static ApplicationException notFound() { return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND); }
}
