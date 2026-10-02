package com.pebble.api.admin.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pebble.api.admin.application.AdminAccountQueryService;
import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.domain.AdminStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(AdminAccountQueryService.class)
class AdminAccountPersistenceTest {

    @Autowired private AdminAccountRepository accounts;
    @Autowired private AdminAccountQueryService queries;

    @Test
    void createsActiveAccountWithTsidAndTimestamps() {
        AdminAccount saved = accounts.saveAndFlush(new AdminAccount(loginId(), "encoded-value", AdminRole.MANAGER));

        assertThat(saved.getId()).isPositive();
        assertThat(saved.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findsAccountsForAuthenticationAndRefreshWithLocks() {
        AdminAccount saved = accounts.saveAndFlush(new AdminAccount(loginId(), "encoded-value", AdminRole.MASTER));

        assertThat(queries.findForLogin(saved.getLoginId())).contains(saved);
        assertThat(queries.findForRefresh(saved.getId())).contains(saved);
    }

    @Test
    void activeCheckRequiresMatchingRole() {
        AdminAccount saved = accounts.saveAndFlush(new AdminAccount(loginId(), "encoded-value", AdminRole.MANAGER));

        assertThat(queries.findActive(saved.getId(), "MANAGER")).isTrue();
        assertThat(queries.findActive(saved.getId(), "MASTER")).isFalse();
        assertThat(queries.findActive(-1, "MANAGER")).isFalse();
    }

    private static String loginId() {
        return "admin-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
