package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.pebble.api.auth.application.UserRefreshTokenService;
import com.pebble.api.auth.application.UserSessionService;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.project.application.ProjectQueryService;
import com.pebble.api.project.domain.Project;
import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.project.infrastructure.persistence.ProjectRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class MemberWithdrawalTransactionTest extends AuthenticationTestSupport {
    @Autowired MemberWithdrawalService withdrawals;
    @Autowired MemberRepository members;
    @Autowired MemberOAuthIdentityRepository identities;
    @Autowired UserSessionService sessions;
    @Autowired ProjectRepository projects;
    @Autowired ProjectQueryService projectQueries;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean UserRefreshTokenService tokens;
    private Member member;
    private String subject;

    @BeforeEach
    void setup() {
        member = members.saveAndFlush(new Member("tx-" + UUID.randomUUID().toString().substring(0, 20), null, MemberStatus.ACTIVE, null, null));
        subject = "withdrawal-tx-" + UUID.randomUUID();
        identities.saveAndFlush(new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject));
    }

    @AfterEach
    void cleanup() {
        reset(tokens);
        tokens.revokeAll(member.getId());
        jdbc.update("delete from project where owner_member_id=?", member.getId());
        jdbc.update("delete from member_oauth_identity where member_id=?", member.getId());
        jdbc.update("delete from member where id=?", member.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authenticationWaitingOnWithdrawalCannotIssueAfterCommit(boolean refresh) throws Exception {
        var original = sessions.login(member.getId());
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var withdrawal = executor.submit(() -> transactions.executeWithoutResult(status -> {
                members.findByIdForWrite(member.getId()).orElseThrow();
                locked.countDown();
                await(release);
                withdrawals.request(member.getId());
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var authentication = executor.submit(() -> {
                    attempted.countDown();
                    return refresh ? sessions.refresh(original.refreshToken()).refreshToken() : sessions.login(member.getId()).refreshToken();
                });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> authentication.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                withdrawal.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> authentication.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AuthException.class);
            } finally { release.countDown(); }
        }
        withdrawals.cancel(OAuthProvider.NAVER, subject);
        assertThatThrownBy(() -> sessions.refresh(original.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(sessions.login(member.getId()).accessToken()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void withdrawalWaitsForAuthenticationThenRevokesItsNewFamily(boolean refresh) throws Exception {
        var original = sessions.login(member.getId());
        CountDownLatch issued = new CountDownLatch(1), release = new CountDownLatch(1), attempted = new CountDownLatch(1);
        var pause = (org.mockito.stubbing.Answer<Object>) invocation -> {
            Object grant = invocation.callRealMethod();
            issued.countDown();
            await(release);
            return grant;
        };
        if (refresh) doAnswer(pause).when(tokens).rotate(any(), any());
        else doAnswer(pause).when(tokens).issue(eq(member.getId()), any(Instant.class));
        AtomicReference<String> issuedToken = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var authentication = executor.submit(() -> issuedToken.set(refresh ? sessions.refresh(original.refreshToken()).refreshToken()
                    : sessions.login(member.getId()).refreshToken()));
            try {
                assertThat(issued.await(5, TimeUnit.SECONDS)).isTrue();
                var withdrawal = executor.submit(() -> { attempted.countDown(); return withdrawals.request(member.getId()); });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> withdrawal.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                authentication.get(10, TimeUnit.SECONDS);
                withdrawal.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        reset(tokens);
        withdrawals.cancel(OAuthProvider.NAVER, subject);
        assertThatThrownBy(() -> sessions.refresh(issuedToken.get())).isInstanceOf(AuthException.class);
    }

    @Test
    void redisFailureRollsBackBothRequestAndCancellation() {
        var original = sessions.login(member.getId());
        doThrow(new RedisConnectionFailureException("test Redis offline")).when(tokens).revokeAll(member.getId());
        assertThatThrownBy(() -> withdrawals.request(member.getId())).isInstanceOf(RedisConnectionFailureException.class);
        reset(tokens);
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(sessions.refresh(original.refreshToken()).accessToken()).isNotBlank();
        withdrawals.request(member.getId());
        Instant scheduled = members.findById(member.getId()).orElseThrow().getWithdrawalScheduledAt();
        doThrow(new RedisConnectionFailureException("test Redis offline")).when(tokens).revokeAll(member.getId());
        assertThatThrownBy(() -> withdrawals.cancel(OAuthProvider.NAVER, subject)).isInstanceOf(RedisConnectionFailureException.class);
        reset(tokens);
        Member pending = members.findById(member.getId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(MemberStatus.WITHDRAWAL_PENDING);
        assertThat(pending.getWithdrawalScheduledAt()).isEqualTo(scheduled);
    }

    @Test
    void databaseRollbackDoesNotRestoreRevokedFamilies() {
        var original = sessions.login(member.getId());
        transactions.executeWithoutResult(status -> { withdrawals.request(member.getId()); status.setRollbackOnly(); });
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThatThrownBy(() -> sessions.refresh(original.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(sessions.login(member.getId()).refreshToken()).isNotBlank();
    }

    @Test
    void cleanupSkipsLockedMemberAndCancellationWaitingOnPurgeCannotResurrectIt() throws Exception {
        withdrawals.request(member.getId());
        jdbc.update("update member set withdrawal_scheduled_at=now() - interval '1 second' where id=?", member.getId());
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var purge = executor.submit(() -> transactions.executeWithoutResult(status -> {
                members.findByIdForWrite(member.getId()).orElseThrow();
                locked.countDown();
                await(release);
                assertThat(withdrawals.deleteExpired(member.getId())).isTrue();
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(() -> withdrawals.deleteExpired(member.getId())).get(2, TimeUnit.SECONDS)).isFalse();
                var cancellation = executor.submit(() -> { attempted.countDown(); withdrawals.cancel(OAuthProvider.NAVER, subject); });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> cancellation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                purge.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> cancellation.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApplicationException.class);
            } finally { release.countDown(); }
        }
        assertThat(members.existsById(member.getId())).isFalse();
        assertThat(identities.findMemberId(OAuthProvider.NAVER, subject)).isEmpty();
    }

    @Test
    void cancellationCommitLetsWaitingLoginCreateFreshSession() throws Exception {
        var original = sessions.login(member.getId());
        withdrawals.request(member.getId());
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancellation = executor.submit(() -> transactions.executeWithoutResult(status -> {
                members.findByIdForWrite(member.getId()).orElseThrow();
                locked.countDown();
                await(release);
                withdrawals.cancel(OAuthProvider.NAVER, subject);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var authentication = executor.submit(() -> { attempted.countDown(); return sessions.login(member.getId()); });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> authentication.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                cancellation.get(10, TimeUnit.SECONDS);
                assertThat(sessions.refresh(authentication.get(10, TimeUnit.SECONDS).refreshToken()).accessToken()).isNotBlank();
            } finally { release.countDown(); }
        }
        assertThatThrownBy(() -> sessions.refresh(original.refreshToken())).isInstanceOf(AuthException.class);
    }

    @Test
    void lockingProjectForInteractionDoesNotImplicitlyLockForeignOwner() throws Exception {
        Project project = projects.saveAndFlush(new Project(member, "project", null, null, null, null,
                ProjectLifecycleStatus.IN_PROGRESS, null, null, ProjectVisibility.PUBLIC));
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var owner = executor.submit(() -> transactions.executeWithoutResult(status -> {
                members.findByIdForWrite(member.getId()).orElseThrow(); locked.countDown(); await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(() -> transactions.execute(status -> projectQueries.findForComments(project.getId(), true)
                        .getOwner().getStatus())).get(2, TimeUnit.SECONDS)).isEqualTo(MemberStatus.ACTIVE);
            } finally { release.countDown(); }
            owner.get(10, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
