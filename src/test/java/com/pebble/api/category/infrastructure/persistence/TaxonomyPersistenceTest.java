package com.pebble.api.category.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.category.domain.Category;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.techstack.domain.TechStack;
import com.pebble.api.techstack.domain.TechStackStatus;
import com.pebble.api.techstack.infrastructure.persistence.TechStackRepository;
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
    private TechStackRepository techStacks;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void persistsTsidTimestampsAndParentWithoutSerializingAnEntityGraph() {
        Category root = categories.saveAndFlush(new Category(null, "Root", "test-root", 0, CategoryStatus.ACTIVE));
        Category child = categories.saveAndFlush(new Category(root, "Child", "test-child", 1, CategoryStatus.ACTIVE));
        TechStack techStack = techStacks.saveAndFlush(new TechStack("Test", "test-techStack", 0, TechStackStatus.ACTIVE));
        entityManager.clear();

        assertThat(root.getId()).isPositive();
        assertThat(root.getCreatedAt()).isNotNull();
        assertThat(root.getUpdatedAt()).isNotNull();
        assertThat(techStack.getId()).isPositive();
        assertThat(techStack.getCreatedAt()).isNotNull();
        assertThat(techStack.getUpdatedAt()).isNotNull();
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
    void rejectsDuplicateTechStackSlug() {
        techStacks.saveAndFlush(new TechStack("One", "test-duplicate", 0, TechStackStatus.ACTIVE));
        assertThatThrownBy(() -> techStacks.saveAndFlush(new TechStack("Two", "test-duplicate", 1, TechStackStatus.INACTIVE)))
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
    void rejectsInvalidTechStackStatusInTheDatabase() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into tech_stack (id, name, slug, display_order, status, created_at, updated_at)
                values (1, 'Invalid', 'test-invalid-status', 0, 'DELETED', now(), now())
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
