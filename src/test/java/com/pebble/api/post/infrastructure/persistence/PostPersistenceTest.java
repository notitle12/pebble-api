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
import com.pebble.api.post.domain.PostTag;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
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
    void persistsTsidTimestampsAndReferencesAndAcceptsMaximumContentLengths() {
        Member author = persistMember();
        Category category = persistCategory();
        Post post = posts.saveAndFlush(new Post(author, category, "t".repeat(200), "s".repeat(500),
                PostVisibility.PUBLIC, null, 1));
        PostBlock block = new PostBlock(post, BlockType.CODE, "x".repeat(50_000), CodeLanguage.JAVA,
                "c".repeat(100), 0);
        entityManager.persist(block);
        entityManager.flush();
        entityManager.clear();

        Post persisted = posts.findById(post.getId()).orElseThrow();
        assertThat(persisted.getId()).isPositive();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
        assertThat(persisted.getPublishedAt()).isNotNull();
        assertThat(persisted.getAuthor().getId()).isEqualTo(author.getId());
        assertThat(persisted.getCategory().getId()).isEqualTo(category.getId());
        assertThat(jdbc.queryForObject("select char_length(content) from post_block where post_id = ?", Integer.class,
                post.getId())).isEqualTo(50_000);
    }

    @Test
    void acceptsNullableSlugAsFallback() {
        Post post = posts.saveAndFlush(new Post(persistMember(), null, "No slug", null, PostVisibility.HIDDEN,
                null, 1));

        assertThat(jdbc.queryForObject("select slug from post where id = ?", String.class, post.getId())).isNull();
    }

    @Test
    void rejectsTitleLongerThanTwoHundredCharacters() {
        assertThatThrownBy(() -> posts.saveAndFlush(new Post(persistMember(), null, "t".repeat(201), null,
                PostVisibility.HIDDEN, null, 1))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSummaryLongerThanFiveHundredCharacters() {
        assertThatThrownBy(() -> posts.saveAndFlush(new Post(persistMember(), null, "title", "s".repeat(501),
                PostVisibility.HIDDEN, null, 1))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingPostAuthor() {
        assertThatThrownBy(() -> insertPost(10_000L, 9_999_999L, 1L, null, "Missing author", null, "HIDDEN", false,
                null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingPostCategory() {
        assertThatThrownBy(() -> insertPost(10_001L, persistMember().getId(), 1L, 9_999_999L, "Missing category", null,
                "HIDDEN", false, null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateBlockOrder() {
        Post post = persistPost();
        entityManager.persist(new PostBlock(post, BlockType.TEXT, "first", null, null, 0));
        entityManager.persist(new PostBlock(post, BlockType.TEXT, "second", null, null, 0));

        // EntityManager 직접 호출에는 Repository의 Spring 예외 변환이 적용되지 않는다.
        assertThatThrownBy(entityManager::flush)
                .isInstanceOfSatisfying(org.hibernate.exception.ConstraintViolationException.class,
                        violation -> assertThat(violation.getConstraintName()).isEqualTo("uk_post_block_order"));
    }

    @Test
    void rejectsNegativeBlockOrder() {
        Post post = persistPost();
        assertThatThrownBy(() -> jdbc.update("""
                insert into post_block (id, post_id, block_type, content, display_order)
                values (20001, ?, 'TEXT', 'body', -1)
                """, post.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCodeBlockWithoutLanguage() {
        Post post = persistPost();
        assertThatThrownBy(() -> jdbc.update("""
                insert into post_block (id, post_id, block_type, content, display_order)
                values (20002, ?, 'CODE', 'body', 0)
                """, post.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsBlockContentLongerThanFiftyThousandCharacters() {
        Post post = persistPost();
        assertThatThrownBy(() -> jdbc.update("""
                insert into post_block (id, post_id, block_type, content, display_order)
                values (20003, ?, 'TEXT', ?, 0)
                """, post.getId(), "x".repeat(50_001))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPostTagDuplicateCompositeKey() {
        Post post = persistPost();
        Tag tag = persistTag();
        entityManager.persist(new PostTag(post, tag));
        entityManager.flush();

        assertThatThrownBy(() -> jdbc.update("""
                insert into post_tag (post_id, tag_id, created_at) values (?, ?, now())
                """, post.getId(), tag.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingPostTagPost() {
        Tag tag = persistTag();
        assertThatThrownBy(() -> jdbc.update("""
                insert into post_tag (post_id, tag_id, created_at) values (30001, ?, now())
                """, tag.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingPostTagTag() {
        Post post = persistPost();
        assertThatThrownBy(() -> jdbc.update("""
                insert into post_tag (post_id, tag_id, created_at) values (?, 30002, now())
                """, post.getId())).isInstanceOf(DataIntegrityViolationException.class);
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

    private Tag persistTag() {
        Tag tag = new Tag("Post test", "post-test-tag", 0, TagStatus.ACTIVE);
        entityManager.persist(tag);
        entityManager.flush();
        return tag;
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
