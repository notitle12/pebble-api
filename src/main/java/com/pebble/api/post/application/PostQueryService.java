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

    public record StonePost(String id, String title, String urlKey, java.time.Instant publishedAt) { }
    public record StonePostPage(java.util.List<StonePost> content, int page, boolean hasNext) { }

    @Transactional(readOnly = true)
    public StonePostPage stonePostPage(long memberId, int page) {
        var rows = jdbc.query("""
                select id, title, coalesce(slug, post_number::text), published_at from post
                where author_member_id=? and visibility_status='PUBLIC' and is_blocked=false and is_draft=false
                and published_at is not null order by published_at desc, id desc limit 101 offset ?
                """, (row, index) -> new StonePost(row.getString(1), row.getString(2), row.getString(3), row.getTimestamp(4).toInstant()), memberId, (long) page * 100);
        return new StonePostPage(java.util.List.copyOf(rows.subList(0, Math.min(100, rows.size()))), page, rows.size() > 100);
    }

    @Transactional(readOnly = true)
    public java.util.List<StonePost> stonePosts(long memberId, java.util.List<Long> ids, boolean lock) {
        if (ids.isEmpty()) return java.util.List.of();
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("member", memberId).addValue("ids", ids);
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
                select id, title, coalesce(slug, post_number::text), published_at from post
                where author_member_id=:member and id in (:ids) and visibility_status='PUBLIC'
                and is_blocked=false and is_draft=false and published_at is not null order by id
                """ + (lock ? " for share" : ""), params,
                (row, index) -> new StonePost(row.getString(1), row.getString(2), row.getString(3), row.getTimestamp(4).toInstant()));
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
