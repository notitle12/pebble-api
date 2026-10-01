package com.pebble.api.board.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.board.domain.Board;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import jakarta.persistence.EntityManager;
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
class BoardPersistenceTest {
    @Autowired BoardRepository boards;
    @Autowired PostRepository posts;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test
    void persistsTreeAndPostReferenceWithStringSafeTsidAndUnicodeName() {
        Member owner = member();
        Board root = boards.saveAndFlush(new Board(owner.getId(), "😀".repeat(50), null, 0));
        Board child = boards.saveAndFlush(new Board(owner.getId(), "child", root.getId(), 1));
        Post post = new Post(owner, null, "title", null, PostVisibility.PUBLIC, null, 1);
        post.changeBoard(child.getId());
        posts.saveAndFlush(post);
        em.clear();
        assertThat(posts.findById(post.getId()).orElseThrow().getBoardId()).isEqualTo(child.getId());
        assertThat(jdbc.queryForObject("select char_length(name) from board where id=?", Integer.class, root.getId())).isEqualTo(50);
        assertThat(jdbc.queryForObject("select created_at is not null and updated_at is not null from board where id=?", Boolean.class, child.getId())).isTrue();
    }

    @Test
    void compositeForeignKeyRejectsParentOfDifferentOwner() {
        Member owner = member();
        Member other = member();
        Board root = boards.saveAndFlush(new Board(owner.getId(), "root", null, 0));
        assertThatThrownBy(() -> boards.saveAndFlush(new Board(other.getId(), "child", root.getId(), 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compositeForeignKeyRejectsPostOfDifferentOwner() {
        Member owner = member();
        Member other = member();
        Board root = boards.saveAndFlush(new Board(owner.getId(), "root", null, 0));
        Post post = new Post(other, null, "title", null, PostVisibility.PUBLIC, null, 1);
        post.changeBoard(root.getId());
        assertThatThrownBy(() -> posts.saveAndFlush(post)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Member member() {
        Member member = new Member("board-db-" + UUID.randomUUID().toString().substring(0, 8), null, MemberStatus.ACTIVE, null, null);
        em.persist(member);
        em.flush();
        return member;
    }
}
