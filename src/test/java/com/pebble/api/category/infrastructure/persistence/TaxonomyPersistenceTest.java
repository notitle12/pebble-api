package com.pebble.api.category.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.category.domain.Category;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.infrastructure.persistence.TagRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TaxonomyPersistenceTest {

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private TagRepository tags;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void persistsTsidTimestampsAndParentWithoutSerializingAnEntityGraph() {
        Category root = categories.saveAndFlush(new Category(null, "Root", "test-root", 0, CategoryStatus.ACTIVE));
        Category child = categories.saveAndFlush(new Category(root, "Child", "test-child", 1, CategoryStatus.ACTIVE));
        Tag tag = tags.saveAndFlush(new Tag("Test", "test-tag", 0, TagStatus.ACTIVE));
        entityManager.clear();

        assertThat(root.getId()).isPositive();
        assertThat(root.getCreatedAt()).isNotNull();
        assertThat(root.getUpdatedAt()).isNotNull();
        assertThat(tag.getId()).isPositive();
        assertThat(tag.getCreatedAt()).isNotNull();
        assertThat(tag.getUpdatedAt()).isNotNull();
        assertThat(categories.findById(child.getId()).orElseThrow().getParent().getId()).isEqualTo(root.getId());
    }

    @Test
    void rejectsDuplicateCategorySlug() {
        categories.saveAndFlush(new Category(null, "One", "test-duplicate", 0, CategoryStatus.ACTIVE));
        assertThatThrownBy(() -> categories.saveAndFlush(
                new Category(null, "Two", "test-duplicate", 1, CategoryStatus.INACTIVE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateTagSlug() {
        tags.saveAndFlush(new Tag("One", "test-duplicate", 0, TagStatus.ACTIVE));
        assertThatThrownBy(() -> tags.saveAndFlush(new Tag("Two", "test-duplicate", 1, TagStatus.INACTIVE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingCategoryParent() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into category (id, parent_id, name, slug, display_order, status, created_at, updated_at)
                values (1, ?, 'Missing parent', 'test-missing-parent', 0, 'ACTIVE', now(), now())
                """, Long.MAX_VALUE)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDeletingAReferencedParent() {
        Category root = categories.saveAndFlush(new Category(null, "Root", "test-root", 0, CategoryStatus.ACTIVE));
        categories.saveAndFlush(new Category(root, "Child", "test-child", 0, CategoryStatus.ACTIVE));
        assertThatThrownBy(() -> jdbc.update("delete from category where id = ?", root.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidCategoryStatusInTheDatabase() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into category (id, name, slug, display_order, status, created_at, updated_at)
                values (1, 'Invalid', 'test-invalid-status', 0, 'DELETED', now(), now())
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidTagStatusInTheDatabase() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into tag (id, name, slug, display_order, status, created_at, updated_at)
                values (1, 'Invalid', 'test-invalid-status', 0, 'DELETED', now(), now())
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
