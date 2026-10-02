package com.pebble.api.comment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.comment.domain.CommentTarget;
import com.pebble.api.comment.presentation.dto.CommentWriteRequest;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class CommentTransactionTest extends AuthenticationTestSupport {
    @Autowired CommentService comments;
    @Autowired PostService posts;
    @Autowired ProjectService projects;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @MockitoBean NaverOAuthGateway naver;
    final List<Long> fixtures = new ArrayList<>();

    @AfterEach
    void cleanup() {
        transactions.executeWithoutResult(status -> fixtures.forEach(id -> {
            jdbc.update("delete from post where author_member_id=?", id);
            jdbc.update("delete from project where owner_member_id=?", id);
            jdbc.update("delete from member where id=?", id);
        }));
    }

    @ParameterizedTest
    @EnumSource(CommentTarget.class)
    void simultaneousPartialUpdatesPreserveOmittedCommentFields(CommentTarget type) throws Exception {
        long owner = member(true);
        long writer = member(false);
        long content = content(type, owner);
        long comment = comments.create(type, content, writer, input("{\"body\":\"original\",\"visibility\":\"PUBLIC\"}", true)).getId();
        var body = input("{\"body\":\"changed\"}", false);
        var secret = input("{\"visibility\":\"SECRET\"}", false);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { await(start); comments.update(type, content, comment, writer, body); });
            var b = executor.submit(() -> { await(start); comments.update(type, content, comment, writer, secret); });
            start.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        String table = type == CommentTarget.POST ? "post_comment" : "project_comment";
        assertThat(jdbc.queryForObject("select body from " + table + " where id=?", String.class, comment)).isEqualTo("changed");
        assertThat(jdbc.queryForObject("select visibility from " + table + " where id=?", String.class, comment)).isEqualTo("SECRET");
    }

    @ParameterizedTest
    @EnumSource(CommentTarget.class)
    void commentCreationWaitsForContentDeletionAndRejectsItAfterCommit(CommentTarget type) throws Exception {
        long owner = member(true);
        long writer = member(false);
        long content = content(type, owner);
        var input = input("{\"body\":\"late\",\"visibility\":\"PUBLIC\"}", true);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deletion = executor.submit(() -> transactions.executeWithoutResult(status -> {
                if (type == CommentTarget.POST) posts.delete(content, owner); else projects.delete(content, owner);
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var creation = executor.submit(() -> { attempted.countDown(); return comments.create(type, content, writer, input); });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> creation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                deletion.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> creation.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApplicationException.class);
            } finally { release.countDown(); }
        }
        String table = type == CommentTarget.POST ? "post_comment" : "project_comment";
        assertThat(jdbc.queryForObject("select count(*) from " + table + " where author_member_id=?", Long.class, writer)).isZero();
    }

    private CommentChanges input(String json, boolean create) throws Exception { return CommentWriteRequest.parse(mapper.readTree(json), create); }
    private long member(boolean profile) {
        Member member = members.saveAndFlush(new Member("comment-" + UUID.randomUUID().toString().substring(0, 8), null, MemberStatus.ACTIVE, null, null));
        fixtures.add(member.getId());
        if (profile) profiles.complete(member.getId(), "comment-blog-" + member.getId(), "comment-" + member.getId(), null);
        return member.getId();
    }
    private long content(CommentTarget type, long owner) throws Exception {
        if (type == CommentTarget.POST) return posts.create(owner, PostWriteRequest.parse(mapper.readTree(
                "{\"title\":\"parent\",\"visibilityStatus\":\"PUBLIC\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"body\"}]}"), true)).post().getId();
        return projects.create(owner, ProjectWriteRequest.parse(mapper.readTree(
                "{\"name\":\"parent\",\"visibilityStatus\":\"PUBLIC\",\"lifecycleStatus\":\"IN_PROGRESS\"}"), true)).project().getId();
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
