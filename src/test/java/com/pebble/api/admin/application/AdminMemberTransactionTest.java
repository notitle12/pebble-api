package com.pebble.api.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.UserRefreshTokenService;
import com.pebble.api.auth.application.UserSessionService;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
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
class AdminMemberTransactionTest extends AuthenticationTestSupport {
    @Autowired AdminMemberService management;
    @Autowired AdminAccountRepository accounts;
    @Autowired MemberRepository members;
    @Autowired UserSessionService sessions;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean UserRefreshTokenService tokens;
    private AdminAccount manager;
    private Member member;

    @BeforeEach
    void setup() {
        manager = accounts.saveAndFlush(new AdminAccount("operator-" + UUID.randomUUID(), "unused-test-hash", AdminRole.MANAGER));
        member = members.saveAndFlush(new Member("tx-" + UUID.randomUUID().toString().substring(0, 20), null, MemberStatus.ACTIVE, null, null));
    }

    @AfterEach
    void cleanup() {
        org.mockito.Mockito.reset(tokens);
        tokens.revokeAll(member.getId());
        jdbc.update("delete from member where id=?", member.getId());
        jdbc.update("delete from admin_account where id=?", manager.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authenticationWaitingOnSuspensionCannotIssueAfterCommit(boolean refresh) throws Exception {
        var original = sessions.login(member.getId());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch change = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var suspension = executor.submit(() -> transactions.executeWithoutResult(status -> {
                members.findByIdForWrite(member.getId()).orElseThrow();
                locked.countDown();
                await(change);
                management.changeStatus(manager.getId(), member.getId(), MemberStatus.SUSPENDED);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var authentication = executor.submit(() -> {
                    attempted.countDown();
                    return refresh ? sessions.refresh(original.refreshToken()).refreshToken()
                            : sessions.login(member.getId()).refreshToken();
                });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> authentication.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                change.countDown();
                suspension.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> authentication.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AuthException.class);
            } finally { change.countDown(); }
        }
        management.changeStatus(manager.getId(), member.getId(), MemberStatus.ACTIVE);
        assertThatThrownBy(() -> sessions.refresh(original.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(sessions.refresh(sessions.login(member.getId()).refreshToken()).accessToken()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suspensionWaitsForActualAuthenticationTransactionThenRevokesItsGrant(boolean refresh) throws Exception {
        var original = sessions.login(member.getId());
        CountDownLatch issued = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        var pause = (org.mockito.stubbing.Answer<Object>) invocation -> {
            Object grant = invocation.callRealMethod();
            issued.countDown();
            await(release);
            return grant;
        };
        if (refresh) org.mockito.Mockito.doAnswer(pause).when(tokens).rotate(any(), any());
        else org.mockito.Mockito.doAnswer(pause).when(tokens).issue(eq(member.getId()), any(Instant.class));
        AtomicReference<String> token = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var authentication = executor.submit(() -> token.set(refresh ? sessions.refresh(original.refreshToken()).refreshToken()
                    : sessions.login(member.getId()).refreshToken()));
            try {
                assertThat(issued.await(5, TimeUnit.SECONDS)).isTrue();
                var suspension = executor.submit(() -> {
                    attempted.countDown();
                    return management.changeStatus(manager.getId(), member.getId(), MemberStatus.SUSPENDED);
                });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> suspension.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                authentication.get(10, TimeUnit.SECONDS);
                suspension.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        org.mockito.Mockito.reset(tokens);
        assertThatThrownBy(() -> tokens.find(token.get())).isInstanceOf(AuthException.class);
        management.changeStatus(manager.getId(), member.getId(), MemberStatus.ACTIVE);
        assertThatThrownBy(() -> sessions.refresh(token.get())).isInstanceOf(AuthException.class);
    }

    @Test
    void redisFailureRollsBackStatusChangeAndKeepsOriginalSession() {
        var original = sessions.login(member.getId());
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("test Redis offline"))
                .when(tokens).revokeAll(member.getId());
        assertThatThrownBy(() -> management.changeStatus(manager.getId(), member.getId(), MemberStatus.SUSPENDED))
                .isInstanceOf(RedisConnectionFailureException.class);
        org.mockito.Mockito.reset(tokens);
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(sessions.refresh(original.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void databaseRollbackDoesNotRestoreRevokedFamilies() {
        var original = sessions.login(member.getId());
        transactions.executeWithoutResult(status -> {
            management.changeStatus(manager.getId(), member.getId(), MemberStatus.SUSPENDED);
            status.setRollbackOnly();
        });
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThatThrownBy(() -> sessions.refresh(original.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(sessions.login(member.getId()).refreshToken()).isNotBlank();
    }

    @Test
    void inactiveOperatorCannotReadOrChangeMemberAtApplicationBoundary() {
        jdbc.update("update admin_account set status=? where id=?", AdminStatus.INACTIVE.name(), manager.getId());
        assertThatThrownBy(() -> management.detail(manager.getId(), member.getId()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(exception -> assertThat(((ApplicationException) exception).error().code()).isEqualTo("INVALID_TOKEN"));
        assertThatThrownBy(() -> management.changeStatus(manager.getId(), member.getId(), MemberStatus.SUSPENDED))
                .isInstanceOf(ApplicationException.class);
        assertThat(members.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
