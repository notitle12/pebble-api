package com.pebble.api.comment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.comment.domain.CommentTarget;
import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.project.domain.Project;
import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.project.infrastructure.persistence.ProjectRepository;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CommentPersistenceTest {
    @Autowired PostRepository posts;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @EnumSource(CommentTarget.class)
    void databaseRejectsOversizedBodies(CommentTarget type) {
        Member author = member();
        long target = content(type);
        assertThatThrownBy(() -> insert(type, target, author.getId(), "x".repeat(2001))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(CommentTarget.class)
    void databaseRejectsMissingParents(CommentTarget type) {
        long author = member().getId();
        assertThatThrownBy(() -> insert(type, 999999999L, author, "body")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(CommentTarget.class)
    void deletedRowsCannotRetainBodies(CommentTarget type) {
        long target = content(type);
        long id = insert(type, target, member().getId(), "body");
        assertThatThrownBy(() -> jdbc.update("update " + table(type) + " set deleted_at=now() where id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(CommentTarget.class)
    void physicalContentDeletionRemovesItsComments(CommentTarget type) {
        long target = content(type);
        insert(type, target, member().getId(), "body");
        String contentTable = type == CommentTarget.POST ? "post" : "project";
        jdbc.update("delete from " + contentTable + " where id=?", target);
        assertThat(jdbc.queryForObject("select count(*) from " + table(type) + " where " + contentTable + "_id=?", Long.class, target)).isZero();
    }

    private long insert(CommentTarget type, long content, long author, String body) {
        String contentColumn = type == CommentTarget.POST ? "post_id" : "project_id";
        long id = TsidGenerator.generate();
        jdbc.update("insert into " + table(type) + "(id," + contentColumn + ",author_member_id,body,visibility,created_at,updated_at) values (?,?,?,?,'PUBLIC',now(),now())",
                id, content, author, body);
        return id;
    }
    private String table(CommentTarget type) { return type == CommentTarget.POST ? "post_comment" : "project_comment"; }
    private Member member() { return members.saveAndFlush(new Member("comment-" + UUID.randomUUID().toString().substring(0, 8), null, MemberStatus.ACTIVE, null, null)); }
    private long content(CommentTarget type) {
        Member owner = member();
        if (type == CommentTarget.POST) return posts.saveAndFlush(new Post(owner, null, "parent", null, PostVisibility.PUBLIC, null, 1)).getId();
        return projects.saveAndFlush(new Project(owner, "parent", null, null, null, null, ProjectLifecycleStatus.IN_PROGRESS, null, null, ProjectVisibility.PUBLIC)).getId();
    }
}
