package com.pebble.api.techstack.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.techstack.domain.TechStack;
import com.pebble.api.techstack.domain.TechStackError;
import com.pebble.api.techstack.domain.TechStackStatus;
import com.pebble.api.techstack.infrastructure.persistence.TechStackRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class TechStackService {
    private final TechStackRepository techStacks;

    public TechStack create(TechStackChanges input) {
        if (techStacks.existsBySlug(input.slug())) throw conflict();
        TechStack techStack = new TechStack(input.name(), input.slug(), input.displayOrder(), TechStackStatus.ACTIVE);
        try { return techStacks.saveAndFlush(techStack); }
        catch (DataIntegrityViolationException exception) { throw translate(exception); }
    }

    public TechStack update(long id, TechStackChanges input) {
        TechStack techStack = techStacks.findForManagementUpdate(id)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
        String slug = input.has("slug") ? input.slug() : techStack.getSlug();
        if (techStacks.existsBySlugAndIdNot(slug, id)) throw conflict();
        techStack.update(input.has("name") ? input.name() : techStack.getName(), slug,
                input.has("displayOrder") ? input.displayOrder() : techStack.getDisplayOrder(),
                input.has("status") ? input.status() : techStack.getStatus());
        try { techStacks.flush(); }
        catch (DataIntegrityViolationException exception) { throw translate(exception); }
        return techStack;
    }

    private RuntimeException translate(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && "uk_tag_slug".equals(violation.getConstraintName())) return conflict();
        }
        return exception;
    }
    private ApplicationException conflict() { return new ApplicationException(TechStackError.TECH_STACK_SLUG_CONFLICT); }
}
