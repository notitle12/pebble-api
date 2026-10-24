package com.pebble.api.insight.infrastructure;

import com.pebble.api.insight.application.ContentInsightsService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentInsightsCleanupJob {
    private final ContentInsightsService insights;
    @Scheduled(fixedDelayString="${pebble.insight.cleanup.delay:60000}",initialDelayString="${pebble.insight.cleanup.initial-delay:60000}")
    public void cleanup() { insights.cleanupExpired(); }
}
