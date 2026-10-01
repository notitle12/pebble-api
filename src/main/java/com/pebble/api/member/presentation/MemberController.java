package com.pebble.api.member.presentation;

import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.presentation.dto.MemberResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private static final String MEMBER_SUBJECT_PREFIX = "member:";

    private final MemberQueryService memberQueryService;

    @GetMapping("/me")
    public ApiResponse<MemberResponse> me(@AuthenticationPrincipal Jwt jwt) {
        // JWT 검증기를 통과한 회원 주체에서만 본인 ID를 읽는다.
        long memberId = Long.parseLong(jwt.getSubject().substring(MEMBER_SUBJECT_PREFIX.length()));
        Member member = memberQueryService.findActiveById(memberId);
        return ApiResponse.of(toResponse(member));
    }

    private MemberResponse toResponse(Member member) {
        return new MemberResponse(
                Long.toString(member.getId()),
                member.getNickname(),
                member.getProfileImageUrl(),
                member.getStatus(),
                member.getCreatedAt());
    }
}
