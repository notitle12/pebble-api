package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.infrastructure.MemberWithdrawalCleanupJob;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "pebble.member.withdrawal-cleanup.enabled=true",
        "pebble.member.withdrawal-cleanup.initial-delay=3600000"
})
class MemberWithdrawalCleanupIntegrationTest extends AuthenticationTestSupport {
    @Autowired MemberWithdrawalService withdrawals;
    @Autowired MemberWithdrawalCleanupJob cleanupJob;
    @Autowired MemberRepository members;
    @Autowired MemberOAuthIdentityRepository identities;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> fixtureMembers = new ArrayList<>();
    private long sharedCategoryId;
    private long sharedTagId;

    @AfterEach
    void removeFixtures() {
        for (Long id : fixtureMembers) {
            jdbc.update("delete from member_oauth_identity where member_id=?", id);
            jdbc.update("delete from post where author_member_id=?", id);
            jdbc.update("delete from project where owner_member_id=?", id);
            for (int i = 0; i < 5; i++) jdbc.update("delete from board where owner_member_id=? and id not in (select parent_id from board where parent_id is not null)", id);
            jdbc.update("delete from member where id=?", id);
        }
        if (sharedCategoryId != 0) jdbc.update("delete from category where id=?", sharedCategoryId);
        if (sharedTagId != 0) jdbc.update("delete from tag where id=?", sharedTagId);
    }

    @Test
    void dueIdsOnlyIncludesPendingAccountsWhoseGracePeriodHasEnded() {
        Member due = member(MemberStatus.ACTIVE);
        Member future = member(MemberStatus.ACTIVE);
        Member active = member(MemberStatus.ACTIVE);
        Member suspended = member(MemberStatus.ACTIVE);
        withdrawals.request(due.getId());
        withdrawals.request(future.getId());
        jdbc.update("update member set withdrawal_scheduled_at=current_timestamp - interval '1 hour' where id=?", due.getId());
        jdbc.update("update member set withdrawal_scheduled_at=current_timestamp + interval '1 hour' where id=?", future.getId());
        jdbc.update("update member set status='SUSPENDED' where id=?", suspended.getId());

        assertThat(withdrawals.dueIds()).contains(due.getId()).doesNotContain(future.getId(), active.getId(), suspended.getId());
    }

    @Test
    void cleanupRollsBackOneBadTreeContinuesAndRetriesItOnNextRun() {
        Member broken = dueMember();
        Member healthy = dueMember();
        Member retainedOwner = member(MemberStatus.ACTIVE);
        long[] shared = ensureSharedClassifications();
        insertBoardChain(broken.getId(), 4);
        long brokenDeepestBoardId = lastBoardIds.get(broken.getId());
        insertBoardChain(healthy.getId(), 3);
        ContentFixture brokenContent = insertOwnedContent(broken.getId(), retainedOwner.getId(), shared[0], shared[1]);
        ContentFixture healthyContent = insertOwnedContent(healthy.getId(), retainedOwner.getId(), shared[0], shared[1]);
        String subject = "withdrawal-cleanup-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(broken, OAuthProvider.NAVER, subject));
        cleanupJob.cleanup();

        assertThat(members.existsById(broken.getId())).isTrue();
        assertThat(identities.findByProviderAndProviderSubject(OAuthProvider.NAVER, subject)).isPresent();
        assertThat(count("board", "owner_member_id", broken.getId())).isEqualTo(7);
        assertContentRetainedAfterRollback(brokenContent);
        assertThat(members.existsById(healthy.getId())).isFalse();
        assertContentRemoved(healthyContent, healthy.getId());
        assertThat(count("post", "author_member_id", retainedOwner.getId())).isEqualTo(2);
        assertThat(count("project", "owner_member_id", retainedOwner.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from category where id=?", Long.class, shared[0])).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tag where id=?", Long.class, shared[1])).isEqualTo(1);

        // 테스트용으로 추가한 초과 깊이 게시판을 지우면 실패한 회원을 다시 파기할 수 있다.
        jdbc.update("delete from board where owner_member_id=? and id=?", broken.getId(), brokenDeepestBoardId);
        cleanupJob.cleanup();
        assertThat(members.existsById(broken.getId())).isFalse();
        assertContentRemoved(brokenContent, broken.getId());
        assertThat(jdbc.queryForObject("select count(*) from post where author_member_id=?", Long.class, retainedOwner.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from project where owner_member_id=?", Long.class, retainedOwner.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from category where id=?", Long.class, shared[0])).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tag where id=?", Long.class, shared[1])).isEqualTo(1);
        assertThat(identities.findByProviderAndProviderSubject(OAuthProvider.NAVER, subject)).isEmpty();
        assertThat(withdrawals.deleteExpired(broken.getId())).isFalse();
    }

    private final java.util.Map<Long, Long> lastBoardIds = new java.util.HashMap<>();

    private Member member(MemberStatus status) {
        Member value = members.saveAndFlush(new Member("cleanup-" + UUID.randomUUID().toString().substring(0, 12), null, status, null, null));
        fixtureMembers.add(value.getId());
        return value;
    }

    private Member dueMember() {
        Member value = member(MemberStatus.ACTIVE);
        withdrawals.request(value.getId());
        jdbc.update("update member set withdrawal_scheduled_at=current_timestamp - interval '1 hour' where id=?", value.getId());
        return value;
    }

    private void insertBoardChain(long ownerId, int depth) {
        Long parent = null;
        Long last = null;
        for (int i = 0; i < depth; i++) {
            long id = TsidGenerator.generate();
            jdbc.update("insert into board(id, owner_member_id, parent_id, name, display_order, created_at, updated_at) values (?, ?, ?, ?, 0, now(), now())",
                    id, ownerId, parent, "node-" + i);
            parent = id;
            last = id;
        }
        lastBoardIds.put(ownerId, last);
    }

    private long[] ensureSharedClassifications() {
        if (sharedCategoryId == 0) {
            sharedCategoryId = TsidGenerator.generate();
            sharedTagId = TsidGenerator.generate();
            jdbc.update("insert into category(id, name, slug, display_order, status, created_at, updated_at) values (?, 'Shared category', ?, 0, 'ACTIVE', now(), now())",
                    sharedCategoryId, "cleanup-category-" + sharedCategoryId);
            jdbc.update("insert into tag(id, name, slug, display_order, status, created_at, updated_at) values (?, 'Shared tag', ?, 0, 'ACTIVE', now(), now())",
                    sharedTagId, "cleanup-tag-" + sharedTagId);
        }
        return new long[]{sharedCategoryId, sharedTagId};
    }

    private ContentFixture insertOwnedContent(long ownerId, long retainedOwner, long categoryId, long tagId) {
        long[] boardIds = insertBoardChainAndIds(ownerId, 3);
        long projectId = TsidGenerator.generate();
        long postId = TsidGenerator.generate();
        long retainedPostId = TsidGenerator.generate();
        long retainedProjectId = TsidGenerator.generate();
        jdbc.update("insert into project(id, owner_member_id, name, lifecycle_status, visibility_status, created_at, updated_at) values (?, ?, 'own project', 'IN_PROGRESS', 'PUBLIC', now(), now())",
                projectId, ownerId);
        jdbc.update("insert into project_feature(id, project_id, title, description, display_order, created_at, updated_at) values (?, ?, 'feature', 'description', 0, now(), now())",
                TsidGenerator.generate(), projectId);
        jdbc.update("insert into project_link(id, project_id, link_type, label, url, display_order, created_at) values (?, ?, 'GITHUB', 'source', 'https://example.test', 0, now())",
                TsidGenerator.generate(), projectId);
        jdbc.update("insert into project_tag(project_id, tag_id, display_order, created_at) values (?, ?, 0, now())", projectId, tagId);
        jdbc.update("insert into post(id, author_member_id, post_number, category_id, title, visibility_status, deleted_at, project_id, board_id, created_at, updated_at) values (?, ?, 1, ?, 'own post', 'DELETED', now(), ?, ?, now(), now())",
                postId, ownerId, categoryId, projectId, boardIds[0]);
        jdbc.update("insert into post_block(id, post_id, block_type, content, display_order) values (?, ?, 'TEXT', 'body', 0)", TsidGenerator.generate(), postId);
        jdbc.update("insert into post_tag(post_id, tag_id, created_at) values (?, ?, now())", postId, tagId);
        jdbc.update("insert into project(id, owner_member_id, name, lifecycle_status, visibility_status, created_at, updated_at) values (?, ?, 'retained project', 'IN_PROGRESS', 'PUBLIC', now(), now())",
                retainedProjectId, retainedOwner);
        long retainedPostNumber = count("post", "author_member_id", retainedOwner) + 1;
        jdbc.update("insert into post(id, author_member_id, post_number, category_id, title, visibility_status, project_id, created_at, updated_at) values (?, ?, ?, ?, 'retained post', 'PUBLIC', ?, now(), now())",
                retainedPostId, retainedOwner, retainedPostNumber, categoryId, retainedProjectId);
        jdbc.update("insert into post_comment(id, post_id, author_member_id, body, visibility, created_at, updated_at) values (?, ?, ?, 'other on own', 'PUBLIC', now(), now())",
                TsidGenerator.generate(), postId, retainedOwner);
        long ownCommentOnRetained = TsidGenerator.generate();
        jdbc.update("insert into post_comment(id, post_id, author_member_id, body, visibility, created_at, updated_at) values (?, ?, ?, 'own on other', 'PUBLIC', now(), now())",
                ownCommentOnRetained, retainedPostId, ownerId);
        jdbc.update("insert into project_comment(id, project_id, author_member_id, body, visibility, created_at, updated_at) values (?, ?, ?, 'other on own', 'PUBLIC', now(), now())",
                TsidGenerator.generate(), projectId, retainedOwner);
        long ownProjectComment = TsidGenerator.generate();
        jdbc.update("insert into project_comment(id, project_id, author_member_id, body, visibility, created_at, updated_at) values (?, ?, ?, 'own on other', 'PUBLIC', now(), now())",
                ownProjectComment, retainedProjectId, ownerId);
        jdbc.update("insert into post_like(id, post_id, member_id, created_at) values (?, ?, ?, now())", TsidGenerator.generate(), postId, retainedOwner);
        long ownPostLike = TsidGenerator.generate();
        jdbc.update("insert into post_like(id, post_id, member_id, created_at) values (?, ?, ?, now())", ownPostLike, retainedPostId, ownerId);
        jdbc.update("insert into project_like(id, project_id, member_id, created_at) values (?, ?, ?, now())", TsidGenerator.generate(), projectId, retainedOwner);
        long ownProjectLike = TsidGenerator.generate();
        jdbc.update("insert into project_like(id, project_id, member_id, created_at) values (?, ?, ?, now())", ownProjectLike, retainedProjectId, ownerId);
        return new ContentFixture(postId, projectId, retainedPostId, retainedProjectId, ownCommentOnRetained,
                ownProjectComment, ownPostLike, ownProjectLike);
    }

    private long[] insertBoardChainAndIds(long ownerId, int depth) {
        Long parent = null;
        long[] ids = new long[depth];
        for (int i = 0; i < depth; i++) {
            long id = TsidGenerator.generate();
            jdbc.update("insert into board(id, owner_member_id, parent_id, name, display_order, created_at, updated_at) values (?, ?, ?, ?, 0, now(), now())",
                    id, ownerId, parent, "content-node-" + i);
            ids[i] = id;
            parent = id;
        }
        lastBoardIds.put(ownerId, ids[depth - 1]);
        return ids;
    }

    private void assertContentRetainedAfterRollback(ContentFixture f) {
        assertThat(count("post", "id", f.ownPostId())).isEqualTo(1);
        assertThat(count("project", "id", f.ownProjectId())).isEqualTo(1);
        assertThat(count("post_block", "post_id", f.ownPostId())).isEqualTo(1);
        assertThat(count("post_tag", "post_id", f.ownPostId())).isEqualTo(1);
        assertThat(count("project_feature", "project_id", f.ownProjectId())).isEqualTo(1);
        assertThat(count("project_link", "project_id", f.ownProjectId())).isEqualTo(1);
        assertThat(count("project_tag", "project_id", f.ownProjectId())).isEqualTo(1);
        assertThat(count("post_comment", "id", f.ownCommentId())).isEqualTo(1);
        assertThat(count("project_comment", "id", f.ownProjectCommentId())).isEqualTo(1);
        assertThat(count("post_like", "id", f.ownPostLikeId())).isEqualTo(1);
        assertThat(count("project_like", "id", f.ownProjectLikeId())).isEqualTo(1);
        assertThat(count("post_comment", "post_id", f.ownPostId())).isEqualTo(1);
        assertThat(count("project_comment", "project_id", f.ownProjectId())).isEqualTo(1);
        assertThat(count("post_like", "post_id", f.ownPostId())).isEqualTo(1);
        assertThat(count("project_like", "project_id", f.ownProjectId())).isEqualTo(1);
    }

    private void assertContentRemoved(ContentFixture f, long ownerId) {
        assertThat(count("post", "id", f.ownPostId())).isZero();
        assertThat(count("project", "id", f.ownProjectId())).isZero();
        assertThat(count("board", "owner_member_id", ownerId)).isZero();
        assertThat(count("post_block", "post_id", f.ownPostId())).isZero();
        assertThat(count("post_tag", "post_id", f.ownPostId())).isZero();
        assertThat(count("project_feature", "project_id", f.ownProjectId())).isZero();
        assertThat(count("project_link", "project_id", f.ownProjectId())).isZero();
        assertThat(count("project_tag", "project_id", f.ownProjectId())).isZero();
        assertThat(count("post_comment", "post_id", f.ownPostId())).isZero();
        assertThat(count("project_comment", "project_id", f.ownProjectId())).isZero();
        assertThat(count("post_like", "post_id", f.ownPostId())).isZero();
        assertThat(count("project_like", "project_id", f.ownProjectId())).isZero();
        assertThat(count("post_comment", "id", f.ownCommentId())).isZero();
        assertThat(count("project_comment", "id", f.ownProjectCommentId())).isZero();
        assertThat(count("post_like", "id", f.ownPostLikeId())).isZero();
        assertThat(count("project_like", "id", f.ownProjectLikeId())).isZero();
        assertThat(count("post", "id", f.retainedPostId())).isEqualTo(1);
        assertThat(count("project", "id", f.retainedProjectId())).isEqualTo(1);
        assertThat(count("post_comment", "post_id", f.retainedPostId())).isZero();
        assertThat(count("project_comment", "project_id", f.retainedProjectId())).isZero();
        assertThat(count("post_like", "post_id", f.retainedPostId())).isZero();
        assertThat(count("project_like", "project_id", f.retainedProjectId())).isZero();
    }

    private record ContentFixture(long ownPostId, long ownProjectId, long retainedPostId, long retainedProjectId,
                                  long ownCommentId, long ownProjectCommentId, long ownPostLikeId,
                                  long ownProjectLikeId) { }

    private long count(String table, String column, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Long.class, id);
    }
}
