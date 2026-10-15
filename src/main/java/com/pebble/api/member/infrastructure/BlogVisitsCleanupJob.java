package com.pebble.api.member.infrastructure;

import com.pebble.api.member.application.BlogVisitsService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 방문이 없는 블로그도 지난 날짜의 중복 식별값을 정리한다. 누적 집계는 보존한다. */
@Component
@EnableScheduling
@RequiredArgsConstructor
public class BlogVisitsCleanupJob {
    private final BlogVisitsService visits;

    @Scheduled(fixedDelayString = "${pebble.member.visit-cleanup.delay:60000}",
            initialDelayString = "${pebble.member.visit-cleanup.initial-delay:60000}")
    public void cleanup() { visits.cleanupExpired(); }
}
