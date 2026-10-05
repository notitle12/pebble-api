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
    private final com.pebble.api.member.application.MemberProfileImages images;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    @GetMapping("/me")
    public ApiResponse<MemberResponse> me(@AuthenticationPrincipal Jwt jwt) {
        // JWT 검증기를 통과한 회원 주체에서만 본인 ID를 읽는다.
        long memberId = Long.parseLong(jwt.getSubject().substring(MEMBER_SUBJECT_PREFIX.length()));
        Member member = memberQueryService.findActiveById(memberId);
        return ApiResponse.of(MemberResponse.from(member, images.url(member)));
    }

    @PostMapping(value = "/me/profile", consumes = "application/json")
    public ApiResponse<MemberResponse> complete(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode json) {
        MemberProfileRequest request = MemberProfileRequest.parse(json, true);
        return ApiResponse.of(response(profiles.save(memberId(jwt), request.blogName(), request.handle(), request.nickname(), request.removeProfileImage(), true, null)));
    }

    @PatchMapping(value = "/me/profile", consumes = "application/json")
    public ApiResponse<MemberResponse> update(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode json) {
        MemberProfileRequest request = MemberProfileRequest.parse(json, false);
        return ApiResponse.of(response(profiles.save(memberId(jwt), request.blogName(), request.handle(), request.nickname(), request.removeProfileImage(), false, null)));
    }

    @GetMapping("/me/profile/availability")
    public ApiResponse<MemberProfileService.Availability> availability(@AuthenticationPrincipal Jwt jwt,
            jakarta.servlet.http.HttpServletRequest request) {
        var params = request.getParameterMap();
        if (params.size() != 2 || !params.containsKey("field") || !params.containsKey("value")
                || params.values().stream().anyMatch(values -> values.length != 1)) throw com.pebble.api.global.media.MediaRequest.invalid();
        return ApiResponse.of(profiles.availability(memberId(jwt), request.getParameter("field"), request.getParameter("value")));
    }

    @PostMapping(value = "/me/profile", consumes = "multipart/form-data")
    public ApiResponse<MemberResponse> completeImage(@AuthenticationPrincipal Jwt jwt,
            org.springframework.web.multipart.MultipartHttpServletRequest request) {
        return saveImage(jwt, request, true);
    }

    @PatchMapping(value = "/me/profile", consumes = "multipart/form-data")
    public ApiResponse<MemberResponse> updateImage(@AuthenticationPrincipal Jwt jwt,
            org.springframework.web.multipart.MultipartHttpServletRequest request) {
        return saveImage(jwt, request, false);
    }

    private ApiResponse<MemberResponse> saveImage(Jwt jwt, org.springframework.web.multipart.MultipartHttpServletRequest request, boolean initial) {
        byte[] source = com.pebble.api.global.media.MediaRequest.file(request, java.util.Set.of("profile"));
        String json = request.getParameter("profile");
        if (json == null || json.length() > 4096) throw com.pebble.api.global.media.MediaRequest.invalid();
        try {
            JsonNode tree = mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            var input = MemberProfileRequest.parse(tree, initial);
            return ApiResponse.of(response(profiles.save(memberId(jwt), input.blogName(), input.handle(), input.nickname(), input.removeProfileImage(), initial, source)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw com.pebble.api.global.media.MediaRequest.invalid();
        }
    }

    private MemberResponse response(Member member) { return MemberResponse.from(member, images.url(member)); }

    private long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject().substring(MEMBER_SUBJECT_PREFIX.length()));
    }
}
