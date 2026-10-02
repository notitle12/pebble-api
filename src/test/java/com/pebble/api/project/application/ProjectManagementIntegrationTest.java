package com.pebble.api.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.application.PostService;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.project.presentation.dto.ProjectWriteRequest;
import com.pebble.api.support.AuthenticationTestSupport;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.infrastructure.persistence.TagRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ProjectManagementIntegrationTest extends AuthenticationTestSupport {
    @Autowired ProjectService projects;
    @Autowired PostService posts;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired TagRepository tags;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    private final List<Long> owners = new ArrayList<>();
    private final List<Long> createdTags = new ArrayList<>();

    @AfterEach
    void cleanup() {
        transactions.executeWithoutResult(status -> owners.forEach(owner -> {
            jdbc.update("delete from post where author_member_id=?", owner);
            jdbc.update("delete from project where owner_member_id=?", owner);
            jdbc.update("delete from member where id=?", owner);
        }));
        transactions.executeWithoutResult(status -> createdTags.forEach(tagId -> {
            jdbc.update("delete from project_tag where tag_id=?", tagId);
            jdbc.update("delete from tag where id=?", tagId);
        }));
    }

    @Test
    void managementSearchUsesLiteralMatchingAndDoesNotDuplicateProjectsForMatchingTags() throws Exception {
        long owner = owner();
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        Tag first = tags.saveAndFlush(new Tag("needle%_x one " + suffix, "needle-one-" + suffix, 1, TagStatus.ACTIVE));
        Tag second = tags.saveAndFlush(new Tag("needle%_x two " + suffix, "needle-two-" + suffix, 2, TagStatus.ACTIVE));
        createdTags.add(first.getId());
        createdTags.add(second.getId());
        long tagged = create(owner, "PUBLIC", "ordinary", "summary", "", List.of(first, second));
        long hidden = create(owner, "HIDDEN", "literal needle%_x title", null, null, List.of());
        long deleted = create(owner, "PUBLIC", "unrelated", null, "retained deleted description", List.of());
        projects.deleteForManagement(deleted);

        var result = projects.listForManagement("NEEDLE%_X", null, null, owner, PageRequest.of(0, 20));
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(view -> view.project().getId()).containsExactlyInAnyOrder(tagged, hidden);
        assertThat(result.getContent().getFirst().detail()).isFalse();
        assertThat(projects.detailForManagement(deleted).project().getDescription()).isEqualTo("retained deleted description");
        assertThat(projects.listForManagement(null, null, null, owner, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(3);
        assertThat(projects.listForManagement(null, ProjectVisibility.DELETED, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
    }

    @Test
    void blockIsIdempotentAndDeletedProjectsCannotBeBlocked() throws Exception {
        long owner = owner();
        long id = create(owner, "PUBLIC", "managed", null, null, List.of());
        projects.setBlockedForManagement(id, 1L, true);
        // PostgreSQL에 저장된 마이크로초 정밀도를 기준으로 실제 상태 보존을 비교한다.
        var first = projects.detailForManagement(id).project();
        var repeated = projects.setBlockedForManagement(id, 2L, true).project();
        assertThat(repeated.getBlockedAt()).isEqualTo(first.getBlockedAt());
        assertThat(repeated.getBlockedByAdminId()).isEqualTo(1L);
        assertThat(repeated.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        projects.setBlockedForManagement(id, 1L, false);
        assertThat(jdbc.queryForObject("select is_blocked from project where id=?", Boolean.class, id)).isFalse();
        projects.deleteForManagement(id);
        assertThatThrownBy(() -> projects.setBlockedForManagement(id, 1L, true))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.error().code()).isEqualTo("CONTENT_DELETED"));
    }

    @Test
    void managementCanDeleteForInactiveOwnerAndDetachesTheirPosts() throws Exception {
        long owner = owner();
        long projectId = create(owner, "PUBLIC", "owned", null, null, List.of());
        long postId = posts.create(owner, PostWriteRequest.parse(mapper.readTree("""
                {"title":"linked","blocks":[{"type":"TEXT","content":"body"}],"visibilityStatus":"PUBLIC","projectId":"%d"}
                """.formatted(projectId)), true)).post().getId();
        jdbc.update("update member set status='SUSPENDED' where id=?", owner);
        projects.setBlockedForManagement(projectId, 1L, true);
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=now(), "
                + "withdrawal_scheduled_at=now()+interval '7 days' where id=?", owner);
        assertThat(projects.listForManagement(null, null, null, owner, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);

        projects.deleteForManagement(projectId);
        projects.deleteForManagement(projectId);
        assertThat(jdbc.queryForObject("select visibility_status from project where id=?", String.class, projectId)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select project_id from post where id=?", Long.class, postId)).isNull();
    }

    private long create(long owner, String visibility, String name, String summary, String description, List<Tag> selected) throws Exception {
        String json = "{\"name\":\"" + name + "\",\"summary\":" + nullable(summary)
                + ",\"description\":" + nullable(description) + ",\"visibilityStatus\":\"" + visibility
                + "\",\"lifecycleStatus\":\"IN_PROGRESS\",\"tagIds\":["
                + String.join(",", selected.stream().map(tag -> "\"" + tag.getId() + "\"").toList()) + "]}";
        return projects.create(owner, ProjectWriteRequest.parse(mapper.readTree(json), true)).project().getId();
    }

    private static String nullable(String value) { return value == null ? "null" : "\"" + value + "\""; }

    private long owner() {
        Member member = members.saveAndFlush(new Member("management-test", null, MemberStatus.ACTIVE, null, null));
        owners.add(member.getId());
        profiles.complete(member.getId(), "management-blog-" + member.getId(), "management-" + member.getId(), null);
        return member.getId();
    }
}
