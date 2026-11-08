package com.pebble.api.post.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.application.PostChanges;
import com.pebble.api.post.application.PostService;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import com.pebble.api.support.AuthenticationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class PostSeriesConcurrencyTest extends AuthenticationTestSupport {
    @Autowired PostSeriesService series;
    @Autowired PostService posts;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;
    private final List<Long> owners = new ArrayList<>();

    @AfterEach
    void cleanupOwnData() {
        transactions.executeWithoutResult(status -> {
            for (long owner : owners) {
                jdbc.update("delete from post where author_member_id=?", owner);
                jdbc.update("delete from post_series where owner_member_id=?", owner);
                jdbc.update("delete from member where id=?", owner);
            }
        });
    }

    @Test
    void assignmentBeforeDeletionIsDetachedWithoutDeletingPost() throws Exception {
        long owner = member();
        long board = create(owner, "board");
        PostChanges input = postInput(board);
        CountDownLatch assigned = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var assignment = executor.submit(() -> transactions.execute(status -> {
                long postId = posts.create(owner, input).post().getId();
                assigned.countDown();
                await(release);
                return postId;
            }));
            assertThat(assigned.await(5, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> series.delete(board, owner));
            try {
                assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            long postId = assignment.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select series_id from post where id=?", Long.class, postId)).isNull();
            assertThat(jdbc.queryForObject("select visibility_status from post where id=?", String.class, postId)).isEqualTo("PUBLIC");
        }
    }

    @Test
    void deletionBeforeAssignmentRejectsDeletedSeriesAndRollsBackPostCreation() throws Exception {
        long owner = member();
        long board = create(owner, "board");
        PostChanges input = postInput(board);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deletion = executor.submit(() -> transactions.executeWithoutResult(status -> {
                series.delete(board, owner);
                deleted.countDown();
                await(release);
            }));
            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            var assignment = executor.submit(() -> {
                try { posts.create(owner, input); return "OK"; }
                catch (ApplicationException exception) { return exception.error().code(); }
            });
            try {
                assertThatThrownBy(() -> assignment.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            deletion.get(10, TimeUnit.SECONDS);
            assertThat(assignment.get(10, TimeUnit.SECONDS)).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(jdbc.queryForObject("select count(*) from post where author_member_id=?", Long.class, owner)).isZero();
    }

    @Test
    void parallelAppendsAndReordersKeepUniqueContiguousPositions() throws Exception {
        long owner = member(), id = create(owner, "series");
        var input = postInput(id);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return posts.create(owner, input).post().getId(); });
            var second = executor.submit(() -> { await(start); return posts.create(owner, input).post().getId(); });
            start.countDown();
            long a = first.get(10, TimeUnit.SECONDS), b = second.get(10, TimeUnit.SECONDS);
            var move = PostWriteRequest.parse(mapper.readTree("{\"seriesDisplayOrder\":0}"), false);
            var moveA = executor.submit(() -> posts.update(a, owner, move));
            var moveB = executor.submit(() -> posts.update(b, owner, move));
            moveA.get(10, TimeUnit.SECONDS); moveB.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForList("select series_display_order from post where series_id=? order by series_display_order", Integer.class, id))
                .containsExactly(0, 1);
    }

    private long create(long owner, String name) {
        return Long.parseLong(series.create(owner, new PostSeriesChanges(name, "")).id());
    }

    @Test void concurrentSeriesReorderAndCreateRemainContiguousAndKeepAllSeries() throws Exception {
        long owner = member(), a = create(owner, "a"), b = create(owner, "b");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return series.update(a, owner, new PostSeriesChanges(null, null, 0)); });
            var second = executor.submit(() -> { await(start); return create(owner, "new"); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS); long c = second.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForList("select id from post_series where owner_member_id=?", Long.class, owner)).containsExactlyInAnyOrder(a, b, c);
            assertThat(jdbc.queryForList("select display_order from post_series where owner_member_id=? order by display_order", Integer.class, owner)).containsExactly(0, 1, 2);
        }
    }
    private long member() {
        long id = transactions.execute(status -> members.saveAndFlush(agreedMember(new Member("series-" + java.util.UUID.randomUUID().toString().substring(0, 8), null,
                MemberStatus.ACTIVE, null, null))).getId());
        owners.add(id);
        profiles.complete(id, "blog-" + id, "series-" + id, null);
        return id;
    }
    private PostChanges postInput(long id) throws Exception {
        return PostWriteRequest.parse(mapper.readTree("{\"title\":\"title\",\"visibilityStatus\":\"PUBLIC\",\"seriesId\":\"" + id
                + "\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"content\"}]}"), true);
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 동기화 제한 시간을 초과했습니다.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
