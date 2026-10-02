package com.pebble.api.admin.application;

import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.category.application.CategoryChanges;
import com.pebble.api.category.application.CategoryQueryService;
import com.pebble.api.category.application.CategoryQueryService.CategoryBranch;
import com.pebble.api.category.application.CategoryService;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.tag.application.TagChanges;
import com.pebble.api.tag.application.TagQueryService;
import com.pebble.api.tag.application.TagService;
import com.pebble.api.tag.domain.Tag;
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
    private final TagService tags;
    private final TagQueryService tagQueries;

    public List<CategoryBranch> listCategories(long actor) { requireOperator(actor); return categoryQueries.findManagementTree(); }
    public CategoryBranch createCategory(long actor, CategoryChanges input) { requireOperator(actor); return categories.create(input); }
    public CategoryBranch updateCategory(long actor, long id, CategoryChanges input) { requireOperator(actor); return categories.update(id, input); }
    public List<Tag> listTags(long actor) { requireOperator(actor); return tagQueries.findForManagement(); }
    public Tag createTag(long actor, TagChanges input) { requireOperator(actor); return tags.create(input); }
    public Tag updateTag(long actor, long id, TagChanges input) { requireOperator(actor); return tags.update(id, input); }

    private void requireOperator(long actorId) {
        var actor = accounts.findForAuthenticationById(actorId)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.INVALID_TOKEN));
        if (actor.getStatus() != AdminStatus.ACTIVE) throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
    }
}
