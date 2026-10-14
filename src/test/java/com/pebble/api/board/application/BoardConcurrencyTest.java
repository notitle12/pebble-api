package com.pebble.api.board.application;

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
class BoardConcurrencyTest extends AuthenticationTestSupport {
    @Autowired BoardService boards;
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
                jdbc.update("delete from board where owner_member_id=?", owner);
                jdbc.update("delete from member where id=?", owner);
            }
        });
    }

    @Test
    void simultaneousOppositeMovesCannotCreateCycle() throws Exception {
        long owner = member();
        long a = create(owner, "a");
        long b = create(owner, "b");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return move(owner, a, b); });
            var second = executor.submit(() -> { await(start); return move(owner, b, a); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "VALIDATION_ERROR");
        }
        assertThat(jdbc.queryForObject("select count(*) from board where owner_member_id=? and parent_id is null", Long.class, owner))
                .isEqualTo(1L);
    }

    @Test
    void simultaneousDuplicateSiblingCreatesAllowOnlyOne() throws Exception {
        long owner = member();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return createResult(owner); });
            var second = executor.submit(() -> { await(start); return createResult(owner); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "VALIDATION_ERROR");
        }
        assertThat(jdbc.queryForObject("select count(*) from board where owner_member_id=?", Long.class, owner)).isEqualTo(1L);
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
            var deletion = executor.submit(() -> boards.delete(board, owner));
            try {
                assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            long postId = assignment.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select board_id from post where id=?", Long.class, postId)).isNull();
            assertThat(jdbc.queryForObject("select visibility_status from post where id=?", String.class, postId)).isEqualTo("PUBLIC");
        }
    }

    @Test
    void deletionBeforeAssignmentRejectsDeletedBoardAndRollsBackPostCreation() throws Exception {
        long owner = member();
        long board = create(owner, "board");
        PostChanges input = postInput(board);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deletion = executor.submit(() -> transactions.executeWithoutResult(status -> {
                boards.delete(board, owner);
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
    void treeSaveSerializesWithAnotherWriterAndRejectsStaleBaseline() throws Exception {
        long owner = member();
        long id = create(owner, "before");
        var input = new BoardTreeChanges(List.of(new BoardTreeChanges.Existing(id, "before", null, 0)),
                List.of(new BoardTreeChanges.Node(id, "draft", List.of())));
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> transactions.executeWithoutResult(status -> {
                boards.update(id, owner, new BoardChanges(Set.of("name"), "elsewhere", null, null));
                changed.countDown(); await(release);
            }));
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
            var save = executor.submit(() -> {
                try { boards.replaceTree(owner, input); return "OK"; }
                catch (ApplicationException exception) { return exception.error().code(); }
            });
            try { assertThatThrownBy(() -> save.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(save.get(10, TimeUnit.SECONDS)).isEqualTo("BOARD_TREE_CHANGED");
        }
        assertThat(jdbc.queryForObject("select name from board where id=?", String.class, id)).isEqualTo("elsewhere");
    }

    @Test
    void treeSaveRollsBackEarlierChangesIfALaterWriteFails() {
        long owner = member();
        long id = create(owner, "before");
        var input = new BoardTreeChanges(List.of(new BoardTreeChanges.Existing(id, "before", null, 0)),
                List.of(new BoardTreeChanges.Node(id, "modified", List.of()),
                        new BoardTreeChanges.Node(null, "valid new", List.of()),
                        new BoardTreeChanges.Node(null, "", List.of())));
        assertThatThrownBy(() -> boards.replaceTree(owner, input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select name from board where id=?", String.class, id)).isEqualTo("before");
        assertThat(jdbc.queryForObject("select count(*) from board where owner_member_id=?", Long.class, owner)).isEqualTo(1L);
    }

    private String move(long owner, long board, long parent) {
        try { boards.update(board, owner, new BoardChanges(Set.of("parentId"), null, parent, null)); return "OK"; }
        catch (ApplicationException exception) { return exception.error().code(); }
    }
    private String createResult(long owner) {
        try { create(owner, "same"); return "OK"; }
        catch (ApplicationException exception) { return exception.error().code(); }
    }
    private long create(long owner, String name) {
        return boards.create(owner, new BoardChanges(Set.of("name"), name, null, 0)).getId();
    }
    private long member() {
        long id = transactions.execute(status -> members.saveAndFlush(new Member("board-" + java.util.UUID.randomUUID().toString().substring(0, 8), null,
                MemberStatus.ACTIVE, null, null)).getId());
        owners.add(id);
        profiles.complete(id, "blog-" + id, "board-" + id, null);
        return id;
    }
    private PostChanges postInput(long board) throws Exception {
        return PostWriteRequest.parse(mapper.readTree("{\"title\":\"title\",\"visibilityStatus\":\"PUBLIC\",\"boardId\":\"" + board
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
