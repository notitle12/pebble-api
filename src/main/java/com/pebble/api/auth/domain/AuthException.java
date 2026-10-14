package com.pebble.api.auth.domain;

import com.pebble.api.global.exception.ApplicationException;

public class AuthException extends ApplicationException {

    public AuthException(AuthError error) {
        super(error);
    }

    public AuthException(AuthError error, String field, String reason) {
        super(error, field, reason);
    }

    @Override
    public AuthError error() {
        return (AuthError) super.error();
    }
}
