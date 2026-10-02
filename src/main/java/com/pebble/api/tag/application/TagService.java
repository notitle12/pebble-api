package com.pebble.api.tag.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagError;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.infrastructure.persistence.TagRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class TagService {
    private final TagRepository tags;

    public Tag create(TagChanges input) {
        if (tags.existsBySlug(input.slug())) throw conflict();
        Tag tag = new Tag(input.name(), input.slug(), input.displayOrder(), TagStatus.ACTIVE);
        try { return tags.saveAndFlush(tag); }
        catch (DataIntegrityViolationException exception) { throw translate(exception); }
    }

    public Tag update(long id, TagChanges input) {
        Tag tag = tags.findForManagementUpdate(id)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
        String slug = input.has("slug") ? input.slug() : tag.getSlug();
        if (tags.existsBySlugAndIdNot(slug, id)) throw conflict();
        tag.update(input.has("name") ? input.name() : tag.getName(), slug,
                input.has("displayOrder") ? input.displayOrder() : tag.getDisplayOrder(),
                input.has("status") ? input.status() : tag.getStatus());
        try { tags.flush(); }
        catch (DataIntegrityViolationException exception) { throw translate(exception); }
        return tag;
    }

    private RuntimeException translate(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && "uk_tag_slug".equals(violation.getConstraintName())) return conflict();
        }
        return exception;
    }
    private ApplicationException conflict() { return new ApplicationException(TagError.TAG_SLUG_CONFLICT); }
}
