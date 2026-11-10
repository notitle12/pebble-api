package com.pebble.api.member.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.BlogFollowRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogFollowService {
    private final MemberQueryService members;
    private final MemberRepository memberRepository;
    private final BlogFollowRepository follows;
    private final MemberProfileImages images;

    public BlogFollowRepository.Counts counts(long memberId) {
        return follows.counts(memberId);
    }

    public Status status(long memberId, String handle) {
        members.findProfileCompleted(memberId);
        return status(memberId, members.findPublicBlog(handle).getId());
    }

    @Transactional
    public Status change(long memberId, String handle, boolean following) {
        long targetId = memberRepository.findIdByHandle(handle)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
        if (targetId == memberId) throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR);
        // 맞구독 요청이 동시에 들어와도 두 회원을 같은 순서로 잠근다.
        memberRepository.lockBlogFollowParticipants(memberId, targetId);
        members.findProfileCompletedForWrite(memberId);
        var target = members.findPublicBlog(handle);
        if (target.getId() != targetId || (following && target.getStatus() != MemberStatus.ACTIVE)) {
            throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        }
        if (following) follows.register(memberId, targetId);
        else follows.cancel(memberId, targetId);
        return status(memberId, targetId);
    }

    public FollowPage list(long memberId, Direction direction, int page, int size) {
        members.findProfileCompleted(memberId);
        var pageable = PageRequest.of(page, size);
        var result = direction == Direction.FOLLOWERS ? memberRepository.findMutualFollowers(memberId, pageable)
                : memberRepository.findFollowingBlogs(memberId, pageable);
        return new FollowPage(result.stream().map(member -> new Profile(member.getHandle(), member.getNickname(), images.url(member))).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }

    private Status status(long memberId, long targetId) {
        return new Status(follows.follows(memberId, targetId), follows.counts(targetId).followers());
    }

    public enum Direction { FOLLOWERS, FOLLOWING }
    public record Status(boolean following, long followerCount) {}
    public record Profile(String handle, String nickname, String profileImageUrl) {}
    public record FollowPage(List<Profile> content, int page, int size, long totalElements, int totalPages, boolean first, boolean last) {}
}
