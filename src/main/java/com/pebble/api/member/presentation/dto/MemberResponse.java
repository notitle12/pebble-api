package com.pebble.api.member.presentation.dto;

import com.pebble.api.member.domain.MemberStatus;
import java.time.Instant;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;

public record MemberResponse(
        String id,
        String nickname,
        String profileImageUrl,
        MemberStatus status,
        Instant createdAt,
        String blogName,
        String handle,
        boolean profileCompleted,
        Instant nicknameChangeAvailableAt,
        Instant blogNameChangeAvailableAt) {

    public static MemberResponse from(Member member) {
        return new MemberResponse(member.getId().toString(), member.getNickname(), member.getProfileImageUrl(), member.getStatus(),
                member.getCreatedAt(), member.getBlogName(), member.getHandle(), member.isProfileCompleted(),
                member.isProfileCompleted() ? member.getNicknameChangedAt().plus(MemberProfileService.CHANGE_COOLDOWN) : null,
                member.isProfileCompleted() ? member.getBlogNameChangedAt().plus(MemberProfileService.CHANGE_COOLDOWN) : null);
    }
}
