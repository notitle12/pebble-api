package com.pebble.api.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.application.PostProjectService;
import com.pebble.api.post.application.PostService;
import com.pebble.api.post.domain.Post;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import com.pebble.api.project.application.ProjectService;
import com.pebble.api.project.domain.Project;
import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.project.infrastructure.persistence.ProjectRepository;
import com.pebble.api.project.presentation.dto.ProjectWriteRequest;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class AdminContentTransactionTest extends AuthenticationTestSupport {
    @Autowired AdminContentService management;
    @Autowired AdminAccountRepository accounts;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired ProjectRepository projects;
    @Autowired PostService postService;
    @Autowired ProjectService projectService;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PostProjectService connections;
    private AdminAccount actor;
    private Member owner;
    private long postId;
    private long projectId;

    @BeforeEach
    void setup() {
        actor = accounts.saveAndFlush(new AdminAccount("moderator-" + UUID.randomUUID(), "unused-test-hash", AdminRole.MANAGER));
        transactions.executeWithoutResult(status -> {
            owner = members.saveAndFlush(new Member("tx-" + UUID.randomUUID().toString().substring(0, 12), null, MemberStatus.ACTIVE, null, null));
            owner.completeProfile("Tx Blog " + owner.getId(), "owner-" + owner.getId(), owner.getNickname(), Instant.now());
            members.flush();
            projectId = projects.saveAndFlush(new Project(owner, "original", null, "body", null, null,
                    ProjectLifecycleStatus.IN_PROGRESS, null, null, ProjectVisibility.PUBLIC)).getId();
            Post post = new Post(owner, null, "original", null, PostVisibility.PUBLIC, "original", 1);
            post.changeProject(projectId);
            postId = posts.saveAndFlush(post).getId();
        });
    }

    @AfterEach
    void cleanup() {
        org.mockito.Mockito.reset(connections);
        transactions.executeWithoutResult(status -> {
            jdbc.update("delete from post where author_member_id=?", owner.getId());
            jdbc.update("delete from project where owner_member_id=?", owner.getId());
            jdbc.update("delete from member where id=?", owner.getId());
            jdbc.update("delete from admin_account where id=?", actor.getId());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void moderationWaitsForOwnerUpdateAndUsesCommittedContent(boolean project) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> transactions.executeWithoutResult(status -> {
                update(project);
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var moderator = executor.submit(() -> {
                    attempted.countDown();
                    if (project) return management.blockProject(actor.getId(), projectId, true).project().getName();
                    return management.blockPost(actor.getId(), postId, true).post().getTitle();
                });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> moderator.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                writer.get(10, TimeUnit.SECONDS);
                assertThat(moderator.get(10, TimeUnit.SECONDS)).isEqualTo("edited");
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownerUpdateWaitingForForceDeleteCannotResurrectContent(boolean project) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var moderator = executor.submit(() -> transactions.executeWithoutResult(status -> {
                if (project) management.deleteProject(actor.getId(), projectId);
                else management.deletePost(actor.getId(), postId);
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var writer = executor.submit(() -> { attempted.countDown(); update(project); return true; });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> writer.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                moderator.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> writer.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApplicationException.class);
            } finally { release.countDown(); }
        }
        assertThat(jdbc.queryForObject("select visibility_status from " + (project ? "project" : "post") + " where id=?",
                String.class, project ? projectId : postId)).isEqualTo("DELETED");
    }

    @Test
    void failedProjectDeletionRollsBackEveryPostConnectionAndProjectState() {
        transactions.executeWithoutResult(status -> {
            for (int i = 2; i <= 4; i++) {
                Post post = new Post(members.findById(owner.getId()).orElseThrow(), null, "linked", null,
                        i == 2 ? PostVisibility.HIDDEN : PostVisibility.PUBLIC, "linked-" + i, i);
                post.changeProject(projectId);
                if (i == 3) post.setBlocked(true, actor.getId());
                if (i == 4) post.delete();
                posts.saveAndFlush(post);
            }
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("test failure after detach");
        }).when(connections).detachForManagement(owner.getId(), projectId);
        assertThatThrownBy(() -> management.deleteProject(actor.getId(), projectId)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from post where project_id=?", Long.class, projectId)).isEqualTo(4);
        assertThat(projects.findById(projectId).orElseThrow().getVisibility()).isEqualTo(ProjectVisibility.PUBLIC);
        org.mockito.Mockito.reset(connections);
        management.deleteProject(actor.getId(), projectId);
        assertThat(jdbc.queryForObject("select count(*) from post where project_id=?", Long.class, projectId)).isZero();
        assertThat(jdbc.queryForList("select visibility_status from post where author_member_id=?", String.class, owner.getId()))
                .containsExactlyInAnyOrder("PUBLIC", "HIDDEN", "PUBLIC", "DELETED");
    }

    @Test
    void inactiveAdministratorCannotInspectOrModifyAtApplicationBoundary() {
        jdbc.update("update admin_account set status='INACTIVE' where id=?", actor.getId());
        assertThatThrownBy(() -> management.detailPost(actor.getId(), postId))
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.error().code()).isEqualTo("INVALID_TOKEN"));
        assertThatThrownBy(() -> management.deleteProject(actor.getId(), projectId)).isInstanceOf(ApplicationException.class);
        assertThat(projects.findById(projectId).orElseThrow().getVisibility()).isEqualTo(ProjectVisibility.PUBLIC);
    }

    private void update(boolean project) {
        try {
            if (project) projectService.update(projectId, owner.getId(), ProjectWriteRequest.parse(mapper.readTree("{\"name\":\"edited\"}"), false));
            else postService.update(postId, owner.getId(), PostWriteRequest.parse(mapper.readTree("{\"title\":\"edited\"}"), false));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
