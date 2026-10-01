package com.pebble.api.global.exception;

import java.util.Objects;

public class ApplicationException extends RuntimeException {

    private final ErrorCode error;

    public ApplicationException(ErrorCode error) {
        super(Objects.requireNonNull(error).message());
        this.error = error;
    }

    public ErrorCode error() {
        return error;
    }
}
