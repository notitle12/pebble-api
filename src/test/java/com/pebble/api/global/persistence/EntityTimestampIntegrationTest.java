package com.pebble.api.global.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import com.pebble.api.board.domain.Board;
import com.pebble.api.comment.domain.PostComment;
import com.pebble.api.comment.domain.CommentVisibility;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class EntityTimestampIntegrationTest extends AuthenticationTestSupport {
    @Autowired EntityManager em;

    private Member member() {
        var member = new Member("timestamp-test", null, MemberStatus.ACTIVE, null, null);
        em.persist(member);
        em.flush();
        return member;
    }

    @Test
    void creationAndUpdateCallbacksApplyThroughMappedSuperclasses() {
        var member = member();
        var board = new Board(member.getId(), "original", null, 0);
        em.persist(board);
        em.flush();
        var created = board.getCreatedAt();
        assertThat(created).isNotNull();
        assertThat(board.getUpdatedAt()).isEqualTo(created);
        assertThat(member.getNicknameChangedAt()).isEqualTo(member.getCreatedAt());
        board.update("changed", null, 1);
        em.flush();
        assertThat(board.getCreatedAt()).isEqualTo(created);
        assertThat(board.getUpdatedAt()).isAfter(created);
        var updated = board.getUpdatedAt();
        em.flush();
        assertThat(board.getUpdatedAt()).isEqualTo(updated);
        em.clear();
        var loaded = em.find(Board.class, board.getId());
        assertThat(loaded.getCreatedAt()).isCloseTo(created, within(1, java.time.temporal.ChronoUnit.MICROS));
        // PostgreSQL은 시각을 마이크로초 정밀도로 저장한다.
        assertThat(loaded.getUpdatedAt()).isCloseTo(updated, within(1, java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void createdOnlyMappingDoesNotGainModificationOrActorColumns() {
        var member = member();
        var identity = new MemberOAuthIdentity(member, OAuthProvider.NAVER, "timestamp-subject");
        em.persist(identity);
        em.flush();
        assertThat(identity.getCreatedAt()).isNotNull();
        var attributes = em.getMetamodel().entity(MemberOAuthIdentity.class).getAttributes().stream()
                .map(jakarta.persistence.metamodel.Attribute::getName).toList();
        assertThat(attributes).contains("createdAt").doesNotContain("updatedAt", "createdBy", "updatedBy", "creationTime");
    }

    @Test
    void nestedCommentInheritanceAndPublicationKeepBusinessTimestampSemantics() {
        var member = member();
        var post = new Post(member, null, "title", null, PostVisibility.PUBLIC, null, 1);
        var published = post.getPublishedAt();
        em.persist(post);
        em.flush();
        assertThat(post.getPublishedAt()).isEqualTo(published);
        var comment = new PostComment(post.getId(), member, "body", CommentVisibility.PUBLIC);
        em.persist(comment);
        em.flush();
        var created = comment.getCreatedAt();
        assertThat(comment.getUpdatedAt()).isEqualTo(created);
        comment.update("changed body", CommentVisibility.PUBLIC);
        em.flush();
        assertThat(comment.getCreatedAt()).isEqualTo(created);
        assertThat(comment.getUpdatedAt()).isAfter(created);
    }
}
