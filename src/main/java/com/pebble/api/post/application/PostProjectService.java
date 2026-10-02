package com.pebble.api.post.application;

import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostProjectService {
    private final PostRepository posts;
    private final MemberQueryService members;

    @Transactional
    public void detach(long memberId, long projectId) {
        // Project 삭제와 Post 배치는 동일 회원 잠금 안에서 직렬화한다.
        members.findActiveForWrite(memberId);
        posts.detachProject(memberId, projectId, Instant.now());
    }

    @Transactional
    public void detachForManagement(long memberId, long projectId) {
        members.findForManagementWrite(memberId);
        posts.detachProject(memberId, projectId, Instant.now());
    }
}
