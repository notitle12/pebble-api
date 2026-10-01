package com.pebble.api.post.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import com.pebble.api.support.AuthenticationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class PostConcurrencyTest extends AuthenticationTestSupport {
    @Autowired PostService service;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired PostRepository posts;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NaverOAuthGateway naver;
    private final List<Long> authorIds = new ArrayList<>();

    @AfterEach
    void removeOnlyCommittedTestData() {
        transactions.executeWithoutResult(status -> {
            for (long authorId : authorIds) {
                jdbc.update("delete from post where author_member_id=?", authorId);
                jdbc.update("delete from member where id=?", authorId);
            }
        });
    }

    @Test
    void concurrentCreatesHaveUniqueContiguousAuthorPositions() throws Exception {
        long authorId = member();
        PostChanges command = createInput(null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return service.create(authorId, command).post().getId(); });
            var second = executor.submit(() -> { await(start); return service.create(authorId, command).post().getId(); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isNotEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForList("select display_order from post where author_member_id=? order by display_order", Integer.class, authorId))
                .containsExactly(0, 1);
    }

    @Test
    void concurrentDuplicateSlugsOfSameAuthorReceiveDistinctPermanentSuffixes() throws Exception {
        long author = member();
        PostChanges command = createInput("same-topic");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return service.create(author, command).post().getSlug(); });
            var second = executor.submit(() -> { await(start); return service.create(author, command).post().getSlug(); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("same-topic", "same-topic-2");
        }
        assertThat(jdbc.queryForList("select post_number from post where author_member_id=? order by post_number", Long.class, author))
                .containsExactly(1L, 2L);
    }

    @Test
    void patchAndDeleteSerializeAndCannotReviveDeletedContent() throws Exception {
        long authorId = member();
        long postId = service.create(authorId, createInput(null)).post().getId();
        service.create(authorId, createInput(null));
        PostChanges changes = PostWriteRequest.parse(mapper.readTree("{\"title\":\"updated\",\"displayOrder\":0}"), false);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> transactions.executeWithoutResult(status -> {
                service.update(postId, authorId, changes);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> { attempted.countDown(); service.delete(postId, authorId); });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            update.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
        }
        var deleted = posts.findById(postId).orElseThrow();
        assertThat(deleted.getVisibility()).isEqualTo(PostVisibility.DELETED);
        assertThat(deleted.getTitle()).isEqualTo("updated");
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(jdbc.queryForList("select display_order from post where author_member_id=? and visibility_status<>'DELETED'", Integer.class, authorId))
                .containsExactly(0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.update(postId, authorId, changes))
                .isInstanceOfSatisfying(ApplicationException.class, exception -> assertThat(exception.error().code()).isEqualTo("CONTENT_DELETED"));
    }

    @Test
    void concurrentPartialPatchesPreserveEachOthersOmittedFields() throws Exception {
        long authorId = member();
        long postId = service.create(authorId, createInput(null)).post().getId();
        PostChanges title = PostWriteRequest.parse(mapper.readTree("{\"title\":\"new title\"}"), false);
        PostChanges summary = PostWriteRequest.parse(mapper.readTree("{\"summary\":\"new summary\"}"), false);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); service.update(postId, authorId, title); });
            var second = executor.submit(() -> { await(start); service.update(postId, authorId, summary); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        var changed = posts.findById(postId).orElseThrow();
        assertThat(changed.getTitle()).isEqualTo("new title");
        assertThat(changed.getSummary()).isEqualTo("new summary");
    }

    private long member() {
        long id = transactions.execute(status -> members.saveAndFlush(new Member("writer-" + java.util.UUID.randomUUID().toString().substring(0,8), null, MemberStatus.ACTIVE, null, null)).getId());
        authorIds.add(id);
        profiles.complete(id, "blog-" + id, "author-" + id, null);
        return id;
    }
    private PostChanges createInput(String slug) throws Exception {
        var input = mapper.createObjectNode().put("title", "title").put("visibilityStatus", "PUBLIC");
        if (slug != null) input.put("slug", slug);
        input.putArray("blocks").addObject().put("type", "TEXT").put("content", "content");
        return PostWriteRequest.parse(input, true);
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
