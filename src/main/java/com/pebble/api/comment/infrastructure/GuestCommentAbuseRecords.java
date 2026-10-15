package com.pebble.api.comment.infrastructure;

import com.pebble.api.comment.domain.CommentTarget;
import java.time.Clock;
import java.time.Duration;
import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 댓글 본문과 분리한 보안 기록. 공개 API·관리자 댓글 DTO로 노출하지 않는다. */
@Component
@RequiredArgsConstructor
public class GuestCommentAbuseRecords {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public void record(CommentTarget target,long commentId,String ip) {
        jdbc.update("insert into guest_abuse_event(target,comment_id,ip_address,created_at) values(?,?,?,?)",
                target.name(),commentId,ip,Timestamp.from(clock.instant()));
    }
    @Scheduled(fixedDelayString="${pebble.comment.abuse-cleanup-delay:60000}",initialDelay=60000)
    @Transactional
    public int cleanup() {
        return jdbc.update("delete from guest_abuse_event where created_at<=?",Timestamp.from(clock.instant().minus(Duration.ofDays(7))));
    }
}
