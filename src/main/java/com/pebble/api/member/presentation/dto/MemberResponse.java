package com.pebble.api.member.presentation.dto;

import com.pebble.api.member.domain.MemberStatus;
import java.time.Instant;

public record MemberResponse(
        String id,
        String nickname,
        String profileImageUrl,
        MemberStatus status,
        Instant createdAt) {
}
