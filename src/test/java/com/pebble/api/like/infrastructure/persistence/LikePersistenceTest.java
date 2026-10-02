package com.pebble.api.like.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LikePersistenceTest {
    @Autowired PostRepository posts;
    @Autowired ProjectRepository projects;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"post", "project"})
    void databaseRejectsDuplicateActiveLikes(String type) {
        long member = member().getId();
        long content = content(type);
        insert(type, content, member);
        assertThatThrownBy(() -> insert(type, content, member)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"post", "project"})
    void databaseRejectsMissingTarget(String type) {
        long member = member().getId();
        assertThatThrownBy(() -> insert(type, 999999999L, member)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"post", "project"})
    void cancellationAllowsNewRowAndPhysicalLikerDeletionRemovesHistory(String type) {
        long member = member().getId();
        long content = content(type);
        insert(type, content, member);
        jdbc.update("update " + type + "_like set deleted_at=clock_timestamp() where member_id=?", member);
        insert(type, content, member);
        assertThat(jdbc.queryForObject("select count(*) from " + type + "_like where " + type + "_id=? and deleted_at is null",
                Long.class, content)).isEqualTo(1);
        jdbc.update("delete from member where id=?", member);
        assertThat(jdbc.queryForObject("select count(*) from " + type + "_like where " + type + "_id=?", Long.class, content)).isZero();
    }

    private void insert(String type, long content, long member) {
        // 대상 종류는 테스트의 고정 ValueSource에서만 가져온다.
        jdbc.update("insert into " + type + "_like (id, " + type + "_id, member_id, created_at) values (?,?,?,clock_timestamp())",
                TsidGenerator.generate(), content, member);
    }
    private Member member() { return members.saveAndFlush(new Member("like-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, MemberStatus.ACTIVE, null, null)); }
    private long content(String type) {
        Member owner = member();
        if (type.equals("post")) return posts.saveAndFlush(new Post(owner, null, "liked", null, PostVisibility.PUBLIC, null, 1)).getId();
        return projects.saveAndFlush(new Project(owner, "liked", null, null, null, null,
                ProjectLifecycleStatus.IN_PROGRESS, null, null, ProjectVisibility.PUBLIC)).getId();
    }
}
