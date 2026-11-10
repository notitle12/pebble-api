package com.pebble.api.member.infrastructure.persistence;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BlogFollowRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public void register(long follower, long followed) {
        jdbc.update("""
                INSERT INTO blog_follow (follower_id, followed_id) VALUES (:follower, :followed)
                ON CONFLICT (follower_id, followed_id) DO NOTHING
                """, Map.of("follower", follower, "followed", followed));
    }

    public void cancel(long follower, long followed) {
        jdbc.update("DELETE FROM blog_follow WHERE follower_id = :follower AND followed_id = :followed",
                Map.of("follower", follower, "followed", followed));
    }

    public boolean follows(long follower, long followed) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM blog_follow WHERE follower_id = :follower AND followed_id = :followed)
                """, Map.of("follower", follower, "followed", followed), Boolean.class));
    }

    public Counts counts(long memberId) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM blog_follow f JOIN member m ON m.id = f.follower_id
                        WHERE f.followed_id = :id AND m.status = 'ACTIVE' AND m.profile_completed_at IS NOT NULL) AS followers,
                       (SELECT count(*) FROM blog_follow f JOIN member m ON m.id = f.followed_id
                        WHERE f.follower_id = :id AND m.status = 'ACTIVE' AND m.profile_completed_at IS NOT NULL) AS following
                """, Map.of("id", memberId), (row, index) -> new Counts(row.getLong("followers"), row.getLong("following")));
    }

    public record Counts(long followers, long following) {}
}
