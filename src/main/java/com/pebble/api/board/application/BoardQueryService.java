package com.pebble.api.board.application;

import com.pebble.api.board.domain.Board;
import com.pebble.api.board.infrastructure.persistence.BoardRepository;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.MemberQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardQueryService {
    private final BoardRepository boards;
    private final MemberQueryService members;

    public List<Board> mine(long memberId) {
        members.findActiveById(memberId);
        return boards.findByOwnerMemberIdAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(memberId);
    }

    public List<Board> publicTree(long ownerId) {
        members.findPublicById(ownerId);
        return boards.findByOwnerMemberIdAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(ownerId);
    }

    public Board requirePublic(long ownerId, long boardId) {
        members.findPublicById(ownerId);
        return resolveForPost(boardId, ownerId);
    }

    // 호출자는 회원 쓰기 잠금을 먼저 획득해 삭제와 배치를 같은 경계에서 직렬화한다.
    public Board resolveForPost(long boardId, long ownerId) {
        return boards.findByIdAndOwnerMemberIdAndDeletedAtIsNull(boardId, ownerId)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
    }
}
