package com.pebble.api.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.infrastructure.bootstrap.AdminBootstrapProperties;
import com.pebble.api.admin.infrastructure.bootstrap.AdminBootstrapRunner;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AdminBootstrapServiceTest extends AuthenticationTestSupport {
    @Autowired AdminBootstrapService bootstrap;
    @Autowired AdminAccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ApplicationContext applicationContext;

    @Test
    void createsHashedMasterOnceAndLeavesItUnchangedOnSubsequentBootstrap() {
        String rawPassword = "Bootstrap-Test-Secret-2026";

        assertThat(bootstrap.bootstrap("bootstrap-master", rawPassword)).isTrue();
        var original = accounts.findByLoginId("bootstrap-master").orElseThrow();
        assertThat(original.getRole()).isEqualTo(AdminRole.MASTER);
        assertThat(original.getPasswordHash()).isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, original.getPasswordHash())).isTrue();

        assertThat(bootstrap.bootstrap("replacement-master", "Different-Secret-2026")).isFalse();
        var afterRerun = accounts.findByLoginId("bootstrap-master").orElseThrow();
        assertThat(afterRerun.getId()).isEqualTo(original.getId());
        assertThat(afterRerun.getLoginId()).isEqualTo(original.getLoginId());
        assertThat(afterRerun.getPasswordHash()).isEqualTo(original.getPasswordHash());
        assertThat(accounts.findByLoginId("replacement-master")).isEmpty();
        assertThat(accounts.findAll().stream().filter(account -> account.getRole() == AdminRole.MASTER).count()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidLoginAndPasswordWithoutCreatingMaster() {
        String validPassword = "Bootstrap-Test-Secret-2026";
        assertThatThrownBy(() -> bootstrap.bootstrap("bad!id", validPassword))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bootstrap.bootstrap("valid-admin", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bootstrap.bootstrap("valid-admin", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bootstrap.bootstrap("valid-admin", "bad" + (char) 0 + "password"))
                .isInstanceOf(IllegalArgumentException.class);
        String unpairedSurrogate = new String(new char[]{(char) 0xD800}) + "-long-enough-password";
        assertThatThrownBy(() -> bootstrap.bootstrap("valid-admin", unpairedSurrogate))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(accounts.existsByRole(AdminRole.MASTER)).isFalse();
    }

    @Test
    void bootstrapRunnerIsAbsentByDefaultAndPropertiesNeverExposeValuesInToString() {
        assertThat(applicationContext.getBeansOfType(AdminBootstrapRunner.class)).isEmpty();

        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        properties.setLoginId("sensitive-test-id");
        properties.setPassword("sensitive-test-password");
        assertThat(properties.toString()).doesNotContain("sensitive-test-id", "sensitive-test-password");
    }
}
