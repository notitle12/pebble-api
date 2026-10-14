package com.pebble.api.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.AdminRefreshTokenService;
import com.pebble.api.auth.application.AdminSessionService;
import com.pebble.api.auth.domain.AuthException;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class AdminAccountTransactionTest extends AuthenticationTestSupport {
    private static final String PASSWORD = "Transaction-Test-Secret-2026";
    @Autowired AdminAccountService management;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminSessionService sessions;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean AdminRefreshTokenService refreshTokens;
    private final ConcurrentLinkedQueue<Long> fixtures = new ConcurrentLinkedQueue<>();
    private AdminAccount master;
    private AdminAccount manager;

    @BeforeEach
    void setup() {
        String hash = passwords.encode(PASSWORD);
        master = accounts.saveAndFlush(new AdminAccount("master-" + UUID.randomUUID(), hash, AdminRole.MASTER));
        manager = accounts.saveAndFlush(new AdminAccount("manager-" + UUID.randomUUID(), hash, AdminRole.MANAGER));
        fixtures.add(master.getId());
        fixtures.add(manager.getId());
    }

    @AfterEach
    void cleanup() {
        org.mockito.Mockito.reset(refreshTokens);
        for (long id : fixtures) {
            refreshTokens.revokeAll(id);
            jdbc.update("delete from admin_account where id=?", id);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loginOrRefreshWaitingOnDeactivationRejectsAfterCommit(boolean refresh) throws Exception {
        var original = sessions.login(manager.getLoginId(), PASSWORD);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deactivation = executor.submit(() -> transactions.executeWithoutResult(status -> {
                management.changeStatus(master.getId(), manager.getId(), AdminStatus.INACTIVE);
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var authentication = executor.submit(() -> {
                    attempted.countDown();
                    return refresh ? sessions.refresh(original.tokens().refreshToken())
                            : sessions.login(manager.getLoginId(), PASSWORD).tokens();
                });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> authentication.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                deactivation.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> authentication.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AuthException.class);
            } finally { release.countDown(); }
        }
        assertThat(accounts.findById(manager.getId()).orElseThrow().getStatus()).isEqualTo(AdminStatus.INACTIVE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deactivationWaitingOnLoginOrRefreshRevokesTheIssuedSession(boolean refresh) throws Exception {
        var original = sessions.login(manager.getLoginId(), PASSWORD);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<AdminSessionService.RefreshGrant> issued = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var authentication = executor.submit(() -> transactions.executeWithoutResult(status -> {
                issued.set(refresh ? sessions.refresh(original.tokens().refreshToken())
                        : sessions.login(manager.getLoginId(), PASSWORD).tokens());
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var deactivation = executor.submit(() -> {
                    attempted.countDown();
                    return management.changeStatus(master.getId(), manager.getId(), AdminStatus.INACTIVE);
                });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> deactivation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                authentication.get(10, TimeUnit.SECONDS);
                deactivation.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        var snapshot = refreshTokens.find(issued.get().refreshToken());
        assertThat(refreshTokens.isSessionActive(manager.getId(), "MANAGER", snapshot.sessionId(), Instant.now())).isFalse();
        assertThatThrownBy(() -> sessions.refresh(issued.get().refreshToken())).isInstanceOf(AuthException.class);
    }

    @Test
    void redisFailureDoesNotCommitAccountDeactivation() {
        var original = sessions.login(manager.getLoginId(), PASSWORD);
        var snapshot = refreshTokens.find(original.tokens().refreshToken());
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("test Redis offline"))
                .when(refreshTokens).revokeAll(manager.getId());
        assertThatThrownBy(() -> management.changeStatus(master.getId(), manager.getId(), AdminStatus.INACTIVE))
                .isInstanceOf(RedisConnectionFailureException.class);
        org.mockito.Mockito.reset(refreshTokens);
        assertThat(accounts.findById(manager.getId()).orElseThrow().getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(refreshTokens.isSessionActive(manager.getId(), "MANAGER", snapshot.sessionId(), Instant.now())).isTrue();
    }

    @Test
    void reactivationWaitsForInactiveAccessValidationBeforeIssuingNewSessions() throws Exception {
        var original = sessions.login(manager.getLoginId(), PASSWORD);
        var snapshot = refreshTokens.find(original.tokens().refreshToken());
        management.changeStatus(master.getId(), manager.getId(), AdminStatus.INACTIVE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var validation = executor.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(sessions.isAccessSessionActive(manager.getId(), "MANAGER", snapshot.sessionId())).isFalse();
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var activation = executor.submit(() -> {
                    attempted.countDown();
                    return management.changeStatus(master.getId(), manager.getId(), AdminStatus.ACTIVE);
                });
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> activation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                validation.get(10, TimeUnit.SECONDS);
                activation.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        var fresh = sessions.login(manager.getLoginId(), PASSWORD);
        var freshSnapshot = refreshTokens.find(fresh.tokens().refreshToken());
        assertThat(refreshTokens.isSessionActive(manager.getId(), "MANAGER", freshSnapshot.sessionId(), Instant.now())).isTrue();
    }

    @Test
    void databaseRollbackNeverRestoresRevokedSessions() {
        var original = sessions.login(manager.getLoginId(), PASSWORD);
        var snapshot = refreshTokens.find(original.tokens().refreshToken());
        transactions.executeWithoutResult(status -> {
            management.changeStatus(master.getId(), manager.getId(), AdminStatus.INACTIVE);
            status.setRollbackOnly();
        });
        assertThat(accounts.findById(manager.getId()).orElseThrow().getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(refreshTokens.isSessionActive(manager.getId(), "MANAGER", snapshot.sessionId(), Instant.now())).isFalse();
        assertThatThrownBy(() -> sessions.refresh(original.tokens().refreshToken())).isInstanceOf(AuthException.class);
    }

    @Test
    void simultaneousCreationOfSameLoginIdProducesOneManager() throws Exception {
        String loginId = "duplicate-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    AdminAccount created = management.create(master.getId(), loginId, PASSWORD);
                    fixtures.add(created.getId());
                    return "CREATED";
                } catch (ApplicationException exception) { return exception.error().code(); }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = java.util.List.of(attempts.get(0).get(10, TimeUnit.SECONDS), attempts.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("CREATED", "DUPLICATE_RESOURCE");
        }
        assertThat(jdbc.queryForObject("select count(*) from admin_account where login_id=?", Long.class, loginId)).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
