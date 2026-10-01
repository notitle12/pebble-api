package com.pebble.api.auth.presentation.dto;

public record NaverLoginResponse(
        String accessToken,
        String tokenType,
        long accessTokenExpiresIn,
        MemberResponse member) {

    public record MemberResponse(String id, String nickname, String profileImageUrl, String status, String role) {
    }
}
