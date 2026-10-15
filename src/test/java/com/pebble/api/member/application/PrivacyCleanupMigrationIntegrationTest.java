package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PrivacyCleanupMigrationIntegrationTest extends AuthenticationTestSupport {
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    @Test void migrationRemovesVisitorIdentifiersAndExpeditesOnlyExistingWithdrawalRequests() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Member active = members.saveAndFlush(new Member("active-" + suffix, null, MemberStatus.ACTIVE, null, null));
        Instant now = Instant.now();
        Member pending = members.saveAndFlush(new Member("pending-" + suffix, null, MemberStatus.WITHDRAWAL_PENDING,
                now.minusSeconds(60), now.plusSeconds(86400)));
        jdbc.update("insert into blog_visit_daily(member_id,visit_date,visitor_hash) values (?,current_date,?)", active.getId(), "a".repeat(64));
        jdbc.update("insert into blog_visit_stats(member_id,total_visitors,visit_date,today_visitors) values (?,12,current_date,2)", active.getId());
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V33__retire_visit_tracking_and_withdrawal_grace.sql"));
            return null;
        });
        em.clear();
        assertThat(jdbc.queryForObject("select count(*) from blog_visit_daily where member_id=?", Long.class, active.getId())).isZero();
        assertThat(jdbc.queryForObject("select total_visitors from blog_visit_stats where member_id=?", Long.class, active.getId())).isEqualTo(12);
        assertThat(members.findById(active.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(members.findById(pending.getId()).orElseThrow().getWithdrawalScheduledAt()).isBeforeOrEqualTo(Instant.now());
    }
}
