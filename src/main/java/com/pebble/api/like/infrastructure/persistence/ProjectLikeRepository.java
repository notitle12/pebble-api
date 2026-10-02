package com.pebble.api.like.infrastructure.persistence;

import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.like.domain.LikeSummary;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjectLikeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public void register(long contentId, long memberId) {
        jdbc.update("""
                insert into project_like (id, project_id, member_id, created_at)
                values (:id, :contentId, :memberId, current_timestamp)
                on conflict (project_id, member_id) where deleted_at is null do nothing
                """, Map.of("id", TsidGenerator.generate(), "contentId", contentId, "memberId", memberId));
    }

    public void cancel(long contentId, long memberId) {
        jdbc.update("""
                update project_like set deleted_at=greatest(clock_timestamp(), created_at)
                where project_id=:contentId and member_id=:memberId and deleted_at is null
                """, Map.of("contentId", contentId, "memberId", memberId));
    }

    public Map<Long, LikeSummary> summaries(Collection<Long> ids, Long requesterId) {
        if (ids.isEmpty()) return Map.of();
        var parameters = new MapSqlParameterSource().addValue("ids", ids).addValue("requesterId", requesterId, java.sql.Types.BIGINT);
        Map<Long, LikeSummary> result = new HashMap<>();
        jdbc.query("""
                select l.project_id, count(*) as like_count,
                       coalesce(bool_or(l.member_id=:requesterId), false) as liked_by_me
                from project_like l join member m on m.id=l.member_id
                where l.project_id in (:ids) and l.deleted_at is null and m.status <> 'WITHDRAWAL_PENDING'
                group by l.project_id
                """, parameters, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                result.put(row.getLong("project_id"), new LikeSummary(row.getLong("like_count"), row.getBoolean("liked_by_me"))));
        return result;
    }
}
