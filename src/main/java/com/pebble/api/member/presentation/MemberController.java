package com.pebble.api.member.presentation;

import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.presentation.dto.MemberProfileRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final MemberProfileService profiles;

    @GetMapping("/me")
    public ApiResponse<MemberResponse> me(@AuthenticationPrincipal Jwt jwt) {
        // JWT 검증기를 통과한 회원 주체에서만 본인 ID를 읽는다.
        long memberId = Long.parseLong(jwt.getSubject().substring(MEMBER_SUBJECT_PREFIX.length()));
        Member member = memberQueryService.findActiveById(memberId);
        return ApiResponse.of(MemberResponse.from(member));
    }

    @PostMapping(value = "/me/profile", consumes = "application/json")
    public ApiResponse<MemberResponse> complete(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode json) {
        MemberProfileRequest request = MemberProfileRequest.parse(json, true);
        return ApiResponse.of(MemberResponse.from(profiles.complete(memberId(jwt), request.blogName(), request.handle(), request.nickname())));
    }

    @PatchMapping(value = "/me/profile", consumes = "application/json")
    public ApiResponse<MemberResponse> update(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode json) {
        MemberProfileRequest request = MemberProfileRequest.parse(json, false);
        return ApiResponse.of(MemberResponse.from(profiles.update(memberId(jwt), request.blogName(), request.nickname())));
    }

    private long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject().substring(MEMBER_SUBJECT_PREFIX.length()));
    }
}
