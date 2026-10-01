package com.pebble.api.project.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.project.domain.Project;
import com.pebble.api.project.domain.ProjectFeature;
import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectLink;
import com.pebble.api.project.domain.ProjectLinkType;
import com.pebble.api.project.domain.ProjectTag;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
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
class ProjectPersistenceTest {
    @Autowired ProjectRepository projects;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void persistsProjectAndOrderedChildrenAtDocumentedLimits() {
        Project project = projects.saveAndFlush(new Project(persistMember(), "n".repeat(120), "s".repeat(500),
                "d".repeat(20_000), "a".repeat(20_000), "e".repeat(10_000),
                ProjectLifecycleStatus.COMPLETED, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"),
                ProjectVisibility.PUBLIC));
        Tag tag = persistTag();
        entityManager.persist(new ProjectFeature(project, "f".repeat(100), "x".repeat(2_000), 0));
        entityManager.persist(new ProjectLink(project, ProjectLinkType.GITHUB, "l".repeat(100),
                "u".repeat(2_048), 0));
        entityManager.persist(new ProjectTag(project, tag, 0));
        entityManager.flush();
        entityManager.clear();

        Project persisted = projects.findById(project.getId()).orElseThrow();
        assertThat(persisted.getOwner().getId()).isPositive();
        assertThat(persisted.getPublishedAt()).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from project_feature where project_id = ?", Long.class,
                project.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from project_link where project_id = ?", Long.class,
                project.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from project_tag where project_id = ?", Long.class,
                project.getId())).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateFeatureOrders() {
        Project project = persistProject();
        entityManager.persist(new ProjectFeature(project, "one", "one", 0));
        entityManager.flush();
        assertThatThrownBy(() -> jdbc.update("""
                insert into project_feature (id, project_id, title, description, display_order, created_at, updated_at)
                values (91001, ?, 'two', 'two', 0, now(), now())
                """, project.getId())).isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    void rejectsDuplicateTagOrders() {
        Project project = persistProject();
        Tag first = persistTag();
        Tag second = persistTag();
        entityManager.persist(new ProjectTag(project, first, 0));
        entityManager.flush();
        assertThatThrownBy(() -> jdbc.update("""
                insert into project_tag (project_id, tag_id, display_order, created_at)
                values (?, ?, 0, now())
                """, project.getId(), second.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingOwner() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into project (id, owner_member_id, name, lifecycle_status, visibility_status, is_blocked,
                  created_at, updated_at) values (92001, 99999999, 'missing', 'IN_PROGRESS', 'HIDDEN', false, now(), now())
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidBlockedMetadata() {
        Member owner = persistMember();
        assertThatThrownBy(() -> jdbc.update("""
                insert into project (id, owner_member_id, name, lifecycle_status, visibility_status, is_blocked,
                  created_at, updated_at) values (92002, ?, 'blocked', 'IN_PROGRESS', 'PUBLIC', true, now(), now())
                """, owner.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Project persistProject() {
        return projects.saveAndFlush(new Project(persistMember(), "Project", null, null, null, null,
                ProjectLifecycleStatus.IN_PROGRESS, null, null, ProjectVisibility.HIDDEN));
    }

    private Member persistMember() {
        Member member = new Member("project-" + UUID.randomUUID().toString().substring(0, 8), null,
                MemberStatus.ACTIVE, null, null);
        entityManager.persist(member);
        entityManager.flush();
        return member;
    }

    private Tag persistTag() {
        String value = UUID.randomUUID().toString();
        Tag tag = new Tag("Project " + value.substring(0, 8), "project-" + value, 0, TagStatus.ACTIVE);
        entityManager.persist(tag);
        entityManager.flush();
        return tag;
    }
}
