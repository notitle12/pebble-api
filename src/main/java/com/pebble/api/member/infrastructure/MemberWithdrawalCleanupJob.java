package com.pebble.api.member.infrastructure;

import com.pebble.api.member.application.MemberWithdrawalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@EnableScheduling
@ConditionalOnProperty(name = "pebble.member.withdrawal-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class MemberWithdrawalCleanupJob {
    private final MemberWithdrawalService withdrawals;

    @Scheduled(fixedDelayString = "${pebble.member.withdrawal-cleanup.delay:60000}",
            initialDelayString = "${pebble.member.withdrawal-cleanup.initial-delay:60000}")
    public void cleanup() {
        for (Long id : withdrawals.dueIds()) {
            try { withdrawals.deleteExpired(id); }
            catch (RuntimeException exception) {
                // 개인정보·SQL/외부 서비스 응답 없이 재시도 사실만 기록한다.
                log.warn("탈퇴 예약 파기에 실패해 다음 실행에서 재시도합니다. exceptionType={}", exception.getClass().getSimpleName());
            }
        }
    }
}
