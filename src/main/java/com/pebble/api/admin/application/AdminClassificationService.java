package com.pebble.api.admin.application;

import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.category.application.CategoryChanges;
import com.pebble.api.category.application.CategoryQueryService;
import com.pebble.api.category.application.CategoryQueryService.CategoryBranch;
import com.pebble.api.category.application.CategoryService;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.techstack.application.TechStackChanges;
import com.pebble.api.techstack.application.TechStackQueryService;
import com.pebble.api.techstack.application.TechStackService;
import com.pebble.api.techstack.domain.TechStack;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminClassificationService {
    private final AdminAccountRepository accounts;
    private final CategoryService categories;
    private final CategoryQueryService categoryQueries;
    private final TechStackService techStacks;
    private final TechStackQueryService techStackQueries;

    public List<CategoryBranch> listCategories(long actor) { requireOperator(actor); return categoryQueries.findManagementTree(); }
    public CategoryBranch createCategory(long actor, CategoryChanges input) { requireOperator(actor); return categories.create(input); }
    public CategoryBranch updateCategory(long actor, long id, CategoryChanges input) { requireOperator(actor); return categories.update(id, input); }
    public List<TechStack> listTechStacks(long actor) { requireOperator(actor); return techStackQueries.findForManagement(); }
    public TechStack createTechStack(long actor, TechStackChanges input) { requireOperator(actor); return techStacks.create(input); }
    public TechStack updateTechStack(long actor, long id, TechStackChanges input) { requireOperator(actor); return techStacks.update(id, input); }

    private void requireOperator(long actorId) {
        var actor = accounts.findForAuthenticationById(actorId)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.INVALID_TOKEN));
        if (actor.getStatus() != AdminStatus.ACTIVE) throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
    }
}
