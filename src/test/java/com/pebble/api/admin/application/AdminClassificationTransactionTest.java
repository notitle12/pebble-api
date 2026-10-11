package com.pebble.api.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.category.application.CategoryChanges;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.application.PostService;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import com.pebble.api.project.application.ProjectService;
import com.pebble.api.project.presentation.dto.ProjectWriteRequest;
import com.pebble.api.support.AuthenticationTestSupport;
import com.pebble.api.techstack.application.TechStackChanges;
import com.pebble.api.techstack.domain.TechStackStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class AdminClassificationTransactionTest extends AuthenticationTestSupport {
    @Autowired AdminClassificationService management;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired PostService posts;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    private final String prefix = "classification-" + UUID.randomUUID();
    private final List<Long> memberIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        transactions.executeWithoutResult(status -> {
            for (Long id : memberIds) {
                jdbc.update("delete from post where author_member_id=?", id);
                jdbc.update("delete from project where owner_member_id=?", id);
                jdbc.update("delete from member where id=?", id);
            }
            jdbc.update("delete from category where slug like ?", prefix + "%");
            jdbc.update("delete from tech_stack where slug like ?", prefix + "%");
        });
    }

    @Test
    void categoryChildCommittedFirstMakesWaitingPostSelectionFail() throws Exception {
        long category = category(null, "root");
        long owner = owner();
        blocked(() -> category(category, "child"), () -> post(owner, category, null), "INVALID_CATEGORY_SELECTION");
        assertThat(jdbc.queryForObject("select count(*) from post where author_member_id=?", Long.class, owner)).isZero();
    }

    @Test
    void postAssignmentCommittedFirstMakesWaitingChildCreationFail() throws Exception {
        long category = category(null, "root");
        long owner = owner();
        blocked(() -> post(owner, category, null), () -> category(category, "child"), "CATEGORY_HIERARCHY_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from category where parent_id=?", Long.class, category)).isZero();
        assertThat(jdbc.queryForObject("select category_id from post where author_member_id=?", Long.class, owner)).isEqualTo(category);
    }

    @Test
    void waitingPostSelectionRejectsCommittedCategoryDeactivation() throws Exception {
        long category = category(null, "root");
        long owner = owner();
        blocked(() -> management.updateCategory(1, category,
                new CategoryChanges(Set.of("status"), null, null, null, null, CategoryStatus.INACTIVE)),
                () -> post(owner, category, null), "INVALID_CATEGORY_SELECTION");
        assertThat(jdbc.queryForObject("select count(*) from post where author_member_id=?", Long.class, owner)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"project"})
    void waitingContentSelectionRejectsCommittedTechStackDeactivation(String type) throws Exception {
        long techStack = techStack("new-techStack");
        long owner = owner();
        blocked(() -> deactivateTechStack(techStack), () -> content(type, owner, techStack), "INACTIVE_TECH_STACK");
        assertThat(jdbc.queryForObject("select count(*) from " + type + " where " +
                (type.equals("post") ? "author_member_id" : "owner_member_id") + "=?", Long.class, owner)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"project"})
    void tagAssignmentCommittedFirstIsPreservedWhenWaitingDeactivationCommits(String type) throws Exception {
        long techStack = techStack("new-techStack");
        long owner = owner();
        blocked(() -> content(type, owner, techStack), () -> deactivateTechStack(techStack), null);
        assertThat(jdbc.queryForObject("select count(*) from " + type + "_tech_stack where tech_stack_id=?", Long.class, techStack)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from tech_stack where id=?", String.class, techStack)).isEqualTo("INACTIVE");
    }

    @Test
    void simultaneousOppositeRootMovesCannotCreateCycle() throws Exception {
        long first = category(null, "first");
        long second = category(null, "second");
        blocked(() -> move(first, second), () -> move(second, first), "CATEGORY_HIERARCHY_CONFLICT");
        assertThat(jdbc.queryForObject("select parent_id from category where id=?", Long.class, first)).isEqualTo(second);
        assertThat(jdbc.queryForObject("select parent_id from category where id=?", Long.class, second)).isNull();
    }

    @Test
    void failedCategoryMoveAndTechStackStateTransactionRollsBackEveryChange() {
        long first = category(null, "first");
        long second = category(null, "second");
        long techStack = techStack("techStack");
        var before = jdbc.queryForMap("select parent_id,updated_at from category where id=?", first);
        var tagBefore = jdbc.queryForMap("select status,updated_at from tech_stack where id=?", techStack);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            move(first, second);
            deactivateTechStack(techStack);
            throw new IllegalStateException("rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("select parent_id,updated_at from category where id=?", first)).isEqualTo(before);
        assertThat(jdbc.queryForMap("select status,updated_at from tech_stack where id=?", techStack)).isEqualTo(tagBefore);
    }

    @Test
    void simultaneousTechStackSlugCreationHasOneWinnerAndOneConflict() throws Exception {
        long firstOwner = 1L;
        var input = new TechStackChanges(Set.of("name", "slug"), "tech_stack", prefix + "-duplicate", 0, null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return createTechStackOutcome(firstOwner, input); });
            var second = executor.submit(() -> { await(start); return createTechStackOutcome(firstOwner, input); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "TECH_STACK_SLUG_CONFLICT");
        }
        assertThat(jdbc.queryForObject("select count(*) from tech_stack where slug=?", Long.class, input.slug())).isEqualTo(1);
    }

    private String createTechStackOutcome(long actor, TechStackChanges input) {
        try { management.createTechStack(actor, input); return "SUCCESS"; }
        catch (ApplicationException exception) { return exception.error().code(); }
    }

    private void blocked(Runnable first, Runnable second, String error) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                first.run();
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var waiter = executor.submit(() -> { attempted.countDown(); second.run(); });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> waiter.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                if (error == null) waiter.get(10, TimeUnit.SECONDS);
                else assertThatThrownBy(() -> waiter.get(10, TimeUnit.SECONDS)).satisfies(exception -> {
                    assertThat(exception.getCause()).isInstanceOf(ApplicationException.class);
                    assertThat(((ApplicationException) exception.getCause()).error().code()).isEqualTo(error);
                });
            } finally { release.countDown(); }
        }
    }
    private long category(Long parent, String suffix) {
        return management.createCategory(1, new CategoryChanges(Set.of("name", "slug", "parentId"),
                suffix, prefix + "-" + suffix, parent, 0, null)).category().getId();
    }
    private void move(long id, long parent) {
        management.updateCategory(1, id, new CategoryChanges(Set.of("parentId"), null, null, parent, null, null));
    }
    private long techStack(String suffix) {
        return management.createTechStack(1, new TechStackChanges(Set.of("name", "slug"), suffix, prefix + "-" + suffix, 0, null)).getId();
    }
    private void deactivateTechStack(long id) {
        management.updateTechStack(1, id, new TechStackChanges(Set.of("status"), null, null, null, TechStackStatus.INACTIVE));
    }
    private long owner() {
        Member member = members.saveAndFlush(new Member("classification-" + UUID.randomUUID().toString().substring(0, 12), null, MemberStatus.ACTIVE, null, null));
        memberIds.add(member.getId());
        profiles.complete(member.getId(), "Class blog " + member.getId(), "class-" + member.getId(), null);
        return member.getId();
    }
    private void post(long owner, Long category, Long techStack) {
        try {
            posts.create(owner, PostWriteRequest.parse(mapper.readTree("{\"title\":\"classification post\",\"visibilityStatus\":\"PUBLIC\",\"categoryId\":" +
                    (category == null ? "null" : "\"" + category + "\"") + ",\"tags\":" +
                    (techStack == null ? "[]" : "[\"" + techStack + "\"]") + ",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"body\"}]}"), true));
        } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }
    private void content(String type, long owner, long techStack) {
        if (type.equals("post")) { post(owner, null, techStack); return; }
        try {
            projects.create(owner, ProjectWriteRequest.parse(mapper.readTree("{\"name\":\"classification project\",\"visibilityStatus\":\"PUBLIC\",\"lifecycleStatus\":\"IN_PROGRESS\",\"techStackIds\":[\"" + techStack + "\"]}"), true));
        } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
