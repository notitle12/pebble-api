package com.pebble.api.like.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class LikeConcurrencyTest extends AuthenticationTestSupport {
    @Autowired PostLikeService postLikes;
    @Autowired ProjectLikeService projectLikes;
    @Autowired PostService posts;
    @Autowired ProjectService projects;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @MockitoBean NaverOAuthGateway naver;
    final List<Long> fixtureMembers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        transactions.executeWithoutResult(status -> fixtureMembers.forEach(id -> {
            jdbc.update("delete from post where author_member_id=?", id);
            jdbc.update("delete from project where owner_member_id=?", id);
            jdbc.update("delete from member where id=?", id);
        }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"post", "project"})
    void concurrentDuplicateAndDifferentMemberRegistrationsPreserveUniqueActiveLikes(String type) throws Exception {
        long owner = member(true);
        long first = member(false);
        long second = member(false);
        long content = content(type, owner);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var a = executor.submit(() -> { await(start); register(type, content, first); });
            var b = executor.submit(() -> { await(start); register(type, content, first); });
            var c = executor.submit(() -> { await(start); register(type, content, second); });
            start.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS); c.get(10, TimeUnit.SECONDS);
        }
        assertThat(active(type, content)).isEqualTo(2);
        CountDownLatch cancel = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { await(cancel); cancel(type, content, first); });
            var b = executor.submit(() -> { await(cancel); cancel(type, content, first); });
            cancel.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertThat(active(type, content)).isEqualTo(1);
        register(type, content, first);
        assertThat(active(type, content)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from " + type + "_like where " + type + "_id=?", Long.class, content)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"post", "project"})
    void likeRegistrationWaitsForVisibilityChangeAndRechecksPublicStatus(String type) throws Exception {
        long owner = member(true);
        long actor = member(false);
        long content = content(type, owner);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var hide = executor.submit(() -> transactions.executeWithoutResult(status -> {
                try {
                    if (type.equals("post")) posts.update(content, owner, PostWriteRequest.parse(mapper.readTree("{\"visibilityStatus\":\"HIDDEN\"}"), false));
                    else projects.update(content, owner, ProjectWriteRequest.parse(mapper.readTree("{\"visibilityStatus\":\"HIDDEN\"}"), false));
                } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var liking = executor.submit(() -> { attempted.countDown(); register(type, content, actor); });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> liking.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                hide.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> liking.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApplicationException.class);
            } finally { release.countDown(); }
        }
        assertThat(active(type, content)).isZero();
    }

    private long active(String type, long id) {
        return jdbc.queryForObject("select count(*) from " + type + "_like where " + type + "_id=? and deleted_at is null", Long.class, id);
    }
    private void register(String type, long content, long member) {
        if (type.equals("post")) postLikes.register(content, member); else projectLikes.register(content, member);
    }
    private void cancel(String type, long content, long member) {
        if (type.equals("post")) postLikes.cancel(content, member); else projectLikes.cancel(content, member);
    }
    private long member(boolean profile) {
        Member member = members.saveAndFlush(new Member("like-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, MemberStatus.ACTIVE, null, null));
        fixtureMembers.add(member.getId());
        if (profile) profiles.complete(member.getId(), "like-blog-" + member.getId(), "like-" + member.getId(), null);
        return member.getId();
    }
    private long content(String type, long owner) throws Exception {
        if (type.equals("post")) return posts.create(owner, PostWriteRequest.parse(mapper.readTree(
                "{\"title\":\"liked\",\"visibilityStatus\":\"PUBLIC\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"body\"}]}"), true)).post().getId();
        return projects.create(owner, ProjectWriteRequest.parse(mapper.readTree(
                "{\"name\":\"liked\",\"visibilityStatus\":\"PUBLIC\",\"lifecycleStatus\":\"IN_PROGRESS\"}"), true)).project().getId();
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
