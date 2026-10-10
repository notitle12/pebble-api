package com.pebble.api.post.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostQueryService {
    private final PostRepository posts;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public record ActivityDay(java.time.LocalDate date, long count) { }

    @Transactional(readOnly = true)
    public java.util.List<ActivityDay> publicActivity(long memberId, java.time.LocalDate from, java.time.LocalDate to) {
        var zone = java.time.ZoneId.of("Asia/Seoul");
        return jdbc.query("""
                select (published_at at time zone 'Asia/Seoul')::date as day, count(*)
                from post where author_member_id=? and visibility_status='PUBLIC'
                and is_blocked=false and is_draft=false and published_at>=? and published_at<?
                group by day order by day
                """, (row, index) -> new ActivityDay(row.getDate(1).toLocalDate(), row.getLong(2)), memberId,
                java.sql.Timestamp.from(from.atStartOfDay(zone).toInstant()),
                java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
    }

    @Transactional
    public com.pebble.api.post.domain.Post findForComments(long id, boolean forWrite) {
        var post = (forWrite ? posts.findByIdForUpdate(id) : posts.findById(id)).orElseThrow(PostQueryService::notFound);
        post.getAuthor().getStatus();
        return post;
    }

    @Transactional
    public void requirePublicForWrite(long id) {
        var post = posts.findByIdForUpdate(id).orElseThrow(PostQueryService::notFound);
        if (post.getVisibility() != PostVisibility.PUBLIC || post.isBlocked()
                || post.getAuthor().getStatus() == MemberStatus.WITHDRAWAL_PENDING) throw notFound();
    }

    private static ApplicationException notFound() {
        return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
    }
}
