package com.pebble.api.member.infrastructure.persistence;

import com.pebble.api.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;

public interface MemberRepository extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {
    boolean existsByNickname(String nickname);
    boolean existsByBlogName(String blogName);
    boolean existsByHandle(String handle);
    Optional<Member> findByHandle(String handle);

    @Query("select m.id from Member m where m.handle = :handle")
    Optional<Long> findIdByHandle(@Param("handle") String handle);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id in (:first, :second) order by m.id")
    List<Member> lockBlogFollowParticipants(@Param("first") long first, @Param("second") long second);

    String FOLLOWING_BLOGS = """
            FROM blog_follow f JOIN member m ON m.id = f.followed_id
            WHERE f.follower_id = :id AND m.status = 'ACTIVE' AND m.profile_completed_at IS NOT NULL
            """;

    @Query(value = "SELECT m.* " + FOLLOWING_BLOGS + " ORDER BY f.created_at DESC, f.followed_id DESC",
            countQuery = "SELECT count(*) " + FOLLOWING_BLOGS, nativeQuery = true)
    Page<Member> findFollowingBlogs(@Param("id") long id, Pageable page);

    String MUTUAL_FOLLOWERS = """
            FROM blog_follow f JOIN member m ON m.id = f.follower_id
            WHERE f.followed_id = :id AND m.status = 'ACTIVE' AND m.profile_completed_at IS NOT NULL
              AND EXISTS (SELECT 1 FROM blog_follow mutual WHERE mutual.follower_id = :id AND mutual.followed_id = f.follower_id)
            """;

    @Query(value = "SELECT m.* " + MUTUAL_FOLLOWERS + " ORDER BY f.created_at DESC, f.follower_id DESC",
            countQuery = "SELECT count(*) " + MUTUAL_FOLLOWERS, nativeQuery = true)
    Page<Member> findMutualFollowers(@Param("id") long id, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForWrite(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findForAuthentication(@Param("id") long id);

    @Query("select m.id from Member m where m.status=com.pebble.api.member.domain.MemberStatus.WITHDRAWAL_PENDING " +
            "and m.withdrawalScheduledAt<=:now order by m.withdrawalScheduledAt,m.id")
    List<Long> findExpiredWithdrawalIds(@Param("now") Instant now, Pageable pageable);

    @Query(value = "select * from member where id=:id for update skip locked", nativeQuery = true)
    Optional<Member> findForWithdrawalCleanup(@Param("id") long id);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Member m where m.id=:id")
    int deleteForWithdrawal(@Param("id") long id);
}
