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

public interface MemberRepository extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {
    boolean existsByNickname(String nickname);
    boolean existsByBlogName(String blogName);
    boolean existsByHandle(String handle);
    Optional<Member> findByHandle(String handle);

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
}
