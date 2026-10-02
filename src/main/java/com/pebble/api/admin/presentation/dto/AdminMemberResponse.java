package com.pebble.api.admin.presentation.dto;

import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import java.time.Instant;

public record AdminMemberResponse(String id, String nickname, String profileImageUrl, String blogName, String handle,
                                  boolean profileCompleted, MemberStatus status, Instant createdAt, Instant updatedAt,
                                  Instant withdrawalRequestedAt, Instant withdrawalScheduledAt) {
    public static AdminMemberResponse from(Member member) {
        return new AdminMemberResponse(member.getId().toString(), member.getNickname(), member.getProfileImageUrl(),
                member.getBlogName(), member.getHandle(), member.isProfileCompleted(), member.getStatus(),
                member.getCreatedAt(), member.getUpdatedAt(), member.getWithdrawalRequestedAt(),
                member.getWithdrawalScheduledAt());
    }
}
