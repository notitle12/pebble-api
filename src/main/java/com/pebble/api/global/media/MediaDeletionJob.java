package com.pebble.api.global.media;

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
@ConditionalOnProperty(name = "pebble.media.r2.enabled", havingValue = "true")
public class MediaDeletionJob {
    private final MediaDeletionQueue queue;

    @Scheduled(
            fixedDelayString = "${pebble.media.deletion-delay:60000}",
            initialDelayString = "${pebble.media.deletion-initial-delay:60000}")
    public void cleanup() {
        for (String key : queue.dueKeys()) {
            try {
                queue.deleteDue(key);
            } catch (RuntimeException exception) {
                log.warn(
                        "미디어 삭제 작업이 실패해 다음 주기에 재시도합니다. exceptionType={}",
                        exception.getClass().getSimpleName());
            }
        }
    }
}
