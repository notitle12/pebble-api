package com.pebble.api.post.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.category.domain.Category;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.post.domain.BlockType;
import com.pebble.api.post.domain.CodeLanguage;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostBlock;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.techstack.domain.TechStack;
import com.pebble.api.techstack.domain.TechStackStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.BeforeEach;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PostPersistenceTest {

    @Autowired
    private PostRepository posts;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedBlockingAdmin() {
        jdbc.update("""
                insert into admin_account (id, login_id, password_hash, role, status, created_at, updated_at)
                values (1, 'test-blocking-admin', 'argon2placeholderhash', 'MANAGER', 'ACTIVE', now(), now())
                on conflict (id) do nothing
                """);
    }

    @Test
    void persistsFreeTagsWithoutCatalogReferences() {
        Post post = persistPost();
        post.changeTags(java.util.List.of("회고", "JPA"));
        posts.flush();
        entityManager.clear();
        assertThat(posts.findById(post.getId()).orElseThrow().getTags()).containsExactly("회고", "JPA");
        assertThat(jdbc.queryForObject("select to_regclass('post_tag') is null", Boolean.class)).isTrue();
    }

    @Test
    void rejectsDuplicateSlugForSameAuthor() {
        Member author = persistMember();
        posts.saveAndFlush(new Post(author, null, "One", null, PostVisibility.HIDDEN, "same-slug", 1));

        assertThatThrownBy(() -> posts.saveAndFlush(new Post(author, null, "Two", null,
                PostVisibility.HIDDEN, "same-slug", 2))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameSlugForDifferentAuthors() {
        posts.saveAndFlush(new Post(persistMember(), null, "One", null, PostVisibility.HIDDEN, "shared-slug", 1));
        Post second = posts.saveAndFlush(new Post(persistMember(), null, "Two", null, PostVisibility.HIDDEN,
                "shared-slug", 1));

        assertThat(second.getSlug()).isEqualTo("shared-slug");
    }

    @Test
    void rejectsDuplicatePostNumberForSameAuthor() {
        Member author = persistMember();
        posts.saveAndFlush(new Post(author, null, "One", null, PostVisibility.HIDDEN, null, 1));

        assertThatThrownBy(() -> posts.saveAndFlush(new Post(author, null, "Two", null,
                PostVisibility.HIDDEN, null, 1))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonPositivePostNumber() {
        assertThatThrownBy(() -> insertPost(10_004L, persistMember().getId(), 0L, null, "Invalid number", null,
                "HIDDEN", false, null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativePostDisplayOrder() {
        Post post = persistPost();
        assertThatThrownBy(() -> jdbc.update("update post set display_order = -1 where id = ?", post.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidVisibility() {
        assertThatThrownBy(() -> insertPost(10_002L, persistMember().getId(), 1L, null, "Invalid visibility", null,
                "UNKNOWN", false, null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInconsistentBlockedMetadata() {
        assertThatThrownBy(() -> insertPost(10_003L, persistMember().getId(), 1L, null, "Invalid block", null,
                "HIDDEN", true, null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Member persistMember() {
        Member member = new Member("pt-" + UUID.randomUUID().toString().substring(0, 8), null,
                MemberStatus.ACTIVE, null, null);
        entityManager.persist(member);
        entityManager.flush();
        return member;
    }

    private Category persistCategory() {
        Category category = new Category(null, "Post test", "post-test-category", 0, CategoryStatus.ACTIVE);
        entityManager.persist(category);
        entityManager.flush();
        return category;
    }

    private TechStack persistTechStack() {
        TechStack techStack = new TechStack("Post test", "post-test-techStack", 0, TechStackStatus.ACTIVE);
        entityManager.persist(techStack);
        entityManager.flush();
        return techStack;
    }

    private Post persistPost() {
        return posts.saveAndFlush(new Post(persistMember(), null, "Post test", null, PostVisibility.HIDDEN,
                null, 1));
    }

    private void insertPost(Long id, Long authorId, Long postNumber, Long categoryId, String title, String summary,
                            String visibility, boolean blocked, Instant blockedAt, Long blockedByAdminId) {
        jdbc.update("""
                insert into post (id, author_member_id, post_number, category_id, title, summary, visibility_status,
                                  is_blocked, blocked_at, blocked_by_admin_id, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """, id, authorId, postNumber, categoryId, title, summary, visibility, blocked, blockedAt,
                blockedByAdminId);
    }
}
