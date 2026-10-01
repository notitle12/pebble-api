package com.pebble.api.auth.presentation.dto;

public record RefreshResponse(String accessToken, String tokenType, long accessTokenExpiresIn) {
}
