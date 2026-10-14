package com.pebble.api.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.global.exception.ApplicationException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MemberWithdrawalTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Test
    void repeatedRequestDoesNotExtendSevenDayDeadline() {
        Member member = new Member("withdrawal", null, MemberStatus.ACTIVE, null, null);
        member.requestWithdrawal(NOW);
        assertThat(member.getWithdrawalRequestedAt()).isEqualTo(NOW);
        assertThat(member.getWithdrawalScheduledAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThatThrownBy(() -> member.requestWithdrawal(NOW.plusSeconds(1)))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> assertThat(((ApplicationException) error).error().code()).isEqualTo("ACCOUNT_WITHDRAWAL_PENDING"));
        assertThat(member.getWithdrawalScheduledAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void cancellationIsAllowedStrictlyBeforeDeadline() {
        Member member = pending();
        member.cancelWithdrawal(NOW.minusNanos(1));
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getWithdrawalRequestedAt()).isNull();
        assertThat(member.getWithdrawalScheduledAt()).isNull();
        for (Instant now : new Instant[]{NOW, NOW.plusNanos(1)}) {
            Member expired = pending();
            assertThatThrownBy(() -> expired.cancelWithdrawal(now)).isInstanceOf(ApplicationException.class)
                    .satisfies(error -> assertThat(((ApplicationException) error).error().code()).isEqualTo("WITHDRAWAL_EXPIRED"));
            assertThat(expired.getStatus()).isEqualTo(MemberStatus.WITHDRAWAL_PENDING);
            assertThat(expired.getWithdrawalScheduledAt()).isEqualTo(NOW);
        }
    }

    private Member pending() {
        return new Member("withdrawal", null, MemberStatus.WITHDRAWAL_PENDING, NOW.minus(Duration.ofDays(7)), NOW);
    }
}
