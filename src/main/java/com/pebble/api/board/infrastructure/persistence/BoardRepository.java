package com.pebble.api.board.infrastructure.persistence;

import com.pebble.api.board.domain.Board;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardRepository extends JpaRepository<Board, Long> {
    List<Board> findByOwnerMemberIdAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(long ownerMemberId);
    Optional<Board> findByIdAndOwnerMemberIdAndDeletedAtIsNull(long id, long ownerMemberId);
}
