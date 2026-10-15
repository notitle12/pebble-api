package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.pebble.api.member.domain.*;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "pebble.legal.published=true")
class MemberAgreementConcurrencyTest extends AuthenticationTestSupport {
    @Autowired MemberRepository members;
    @Autowired MemberAgreementService agreements;
    @Autowired JdbcTemplate jdbc;
    @Test void concurrentAcceptanceCreatesOneImmutableReceipt() throws Exception {
        var member = members.saveAndFlush(new Member("agreement-"+UUID.randomUUID().toString().substring(0,10), null, MemberStatus.ACTIVE, null, null));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<Instant> action = () -> { start.await(); return agreements.accept(member.getId(), MemberAgreement.TERMS_VERSION, MemberAgreement.PRIVACY_VERSION).acceptedAt(); };
            var one = pool.submit(action); var two = pool.submit(action); start.countDown();
            assertThat(one.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(two.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("select count(*) from member_agreement where member_id=?", Long.class, member.getId())).isEqualTo(1L);
        } finally { members.deleteById(member.getId()); }
    }
}
