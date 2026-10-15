package com.pebble.api.member.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DailyVisitorCookieTest {
    @Test void expiresAtTheNextKoreanMidnightRatherThanPersistingForMonths() {
        var cookie=BlogToolsController.dailyVisitorCookie("visitor",Instant.parse("2026-10-15T12:00:00Z"),true);
        assertThat(cookie.getMaxAge().toSeconds()).isEqualTo(10800);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/blogs");
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
    }
    @Test void aVisitAtKoreanMidnightStartsANewDayAndNeverGetsANonpositiveLifetime() {
        assertThat(BlogToolsController.dailyVisitorCookie("visitor",Instant.parse("2026-10-15T15:00:00Z"),false).getMaxAge().toSeconds()).isEqualTo(86400);
        assertThat(BlogToolsController.dailyVisitorCookie("visitor",Instant.parse("2026-10-15T14:59:59.999Z"),false).getMaxAge().toSeconds()).isEqualTo(1);
    }
}
