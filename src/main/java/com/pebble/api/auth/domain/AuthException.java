package com.pebble.api.auth.domain;

import com.pebble.api.global.exception.ApplicationException;

public class AuthException extends ApplicationException {

    public AuthException(AuthError error) {
        super(error);
    }

    @Override
    public AuthError error() {
        return (AuthError) super.error();
    }
}
