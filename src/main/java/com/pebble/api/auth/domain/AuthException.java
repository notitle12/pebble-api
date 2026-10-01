package com.pebble.api.auth.domain;

public class AuthException extends RuntimeException {

    private final AuthError error;

    public AuthException(AuthError error) {
        super(error.message());
        this.error = error;
    }

    public AuthError error() {
        return error;
    }
}
