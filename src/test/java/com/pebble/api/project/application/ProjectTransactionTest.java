package com.pebble.api.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.project.application.ProjectChanges.LinkInput;
import com.pebble.api.project.domain.ProjectLinkType;
import com.pebble.api.project.presentation.dto.ProjectWriteRequest;
import com.pebble.api.support.AuthenticationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectTransactionTest extends AuthenticationTestSupport {
    @Autowired ProjectService service;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @MockitoBean NaverOAuthGateway naver;
    private final List<Long> owners = new ArrayList<>();

    @AfterEach
    void removeOnlyCommittedTestFixtures() {
        transactions.executeWithoutResult(status -> owners.forEach(owner -> {
            jdbc.update("delete from project where owner_member_id=?", owner);
            jdbc.update("delete from member where id=?", owner);
        }));
    }

    @Test
    void concurrentPartialUpdatesPreserveOmittedFields() throws Exception {
        long owner = owner();
        long id = create(owner);
        ProjectChanges name = input("{\"name\":\"new name\"}");
        ProjectChanges summary = input("{\"summary\":\"new summary\"}");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); service.update(id, owner, name); });
            var second = executor.submit(() -> { await(start); service.update(id, owner, summary); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select name from project where id=?", String.class, id)).isEqualTo("new name");
        assertThat(jdbc.queryForObject("select summary from project where id=?", String.class, id)).isEqualTo("new summary");
    }

    @Test
    void deletionWaitsForUpdateAndCannotBeReversed() throws Exception {
        long owner = owner();
        long id = create(owner);
        ProjectChanges changes = input("{\"name\":\"updated\"}");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> transactions.executeWithoutResult(status -> {
                service.update(id, owner, changes);
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var deletion = executor.submit(() -> { attempted.countDown(); service.delete(id, owner); });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                update.get(10, TimeUnit.SECONDS);
                deletion.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertThat(jdbc.queryForObject("select visibility_status from project where id=?", String.class, id)).isEqualTo("DELETED");
        assertThatThrownBy(() -> service.update(id, owner, changes)).isInstanceOfSatisfying(ApplicationException.class,
                error -> assertThat(error.error().code()).isEqualTo("CONTENT_DELETED"));
    }

    @Test
    void childPersistenceFailureRollsBackParentAndEarlierReplacement() throws Exception {
        long owner = owner();
        long id = create(owner);
        ProjectChanges parsed = input("{\"name\":\"must roll back\",\"features\":[{\"title\":\"replacement\",\"description\":\"replacement\"}]}");
        // 하위 저장 제약 실패로 앞서 반영한 부모·주요 기능까지 모두 롤백되는지 확인한다.
        ProjectChanges invalid = new ProjectChanges(Set.of("name", "features", "links"), parsed.name(), null, null,
                null, null, null, null, null, null, parsed.features(),
                List.of(new LinkInput(ProjectLinkType.OTHER, null, "https://example.com", -1)), null);
        assertThatThrownBy(() -> service.update(id, owner, invalid)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select name from project where id=?", String.class, id)).isEqualTo("original");
        assertThat(jdbc.queryForObject("select title from project_feature where project_id=?", String.class, id)).isEqualTo("original feature");
        assertThat(jdbc.queryForObject("select count(*) from project_link where project_id=?", Long.class, id)).isZero();
    }

    private long create(long owner) throws Exception {
        return service.create(owner, ProjectWriteRequest.parse(mapper.readTree("""
                {"name":"original","visibilityStatus":"PUBLIC","lifecycleStatus":"IN_PROGRESS",
                 "features":[{"title":"original feature","description":"original"}]}
                """), true)).project().getId();
    }

    private ProjectChanges input(String json) throws Exception { return ProjectWriteRequest.parse(mapper.readTree(json), false); }
    private long owner() {
        Member member = members.saveAndFlush(new Member("transaction-test", null, MemberStatus.ACTIVE, null, null));
        owners.add(member.getId());
        profiles.complete(member.getId(), "project-blog-" + member.getId(), "project-" + member.getId(), null);
        return member.getId();
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
