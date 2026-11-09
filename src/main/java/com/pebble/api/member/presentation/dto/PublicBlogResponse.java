package com.pebble.api.member.presentation.dto;

import com.pebble.api.member.domain.Member;

/** 공개 블로그 정보만 노출한다. 회원 상태·인증 정보는 포함하지 않는다. */
public record PublicBlogResponse(String id, String handle, String nickname, String blogName, String profileImageUrl,
                                 long followerCount, long followingCount) {
    public static PublicBlogResponse from(Member member, String imageUrl, long followerCount, long followingCount) {
        return new PublicBlogResponse(member.getId().toString(), member.getHandle(), member.getNickname(),
                member.getBlogName(), imageUrl, followerCount, followingCount);
    }
}
