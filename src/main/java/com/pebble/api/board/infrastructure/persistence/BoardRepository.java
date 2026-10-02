package com.pebble.api.board.infrastructure.persistence;

import com.pebble.api.board.domain.Board;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface BoardRepository extends JpaRepository<Board, Long> {
    boolean existsByOwnerMemberId(Long memberId);

    @Modifying
    @Query(value = "delete from board where owner_member_id=:memberId and id not in " +
            "(select parent_id from board where owner_member_id=:memberId and parent_id is not null)", nativeQuery = true)
    int deleteLeavesForMember(@Param("memberId") long memberId);
    List<Board> findByOwnerMemberIdAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(long ownerMemberId);
    Optional<Board> findByIdAndOwnerMemberIdAndDeletedAtIsNull(long id, long ownerMemberId);
}
