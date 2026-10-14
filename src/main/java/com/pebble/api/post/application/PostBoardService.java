package com.pebble.api.post.application;

import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostBoardService {
    private final PostRepository posts;
    private final MemberQueryService members;

    @Transactional
    public void detach(long memberId, long boardId) {
        // 삭제 조정과 Post 배치는 회원 잠금 뒤에 수행해 삭제된 게시판 참조를 막는다.
        members.findActiveForWrite(memberId);
        posts.detachBoard(memberId, boardId, Instant.now());
    }
}
