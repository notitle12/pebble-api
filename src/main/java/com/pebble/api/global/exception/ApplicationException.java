package com.pebble.api.global.exception;

import java.util.Objects;
import java.util.List;

public class ApplicationException extends RuntimeException {

    private final ErrorCode error;
    private final List<Violation> violations;

    public ApplicationException(ErrorCode error) {
        super(Objects.requireNonNull(error).message());
        this.error = error;
        this.violations = List.of();
    }

    public ApplicationException(ErrorCode error, String field, String reason) {
        super(Objects.requireNonNull(error).message());
        this.error = error;
        this.violations = List.of(new Violation(field, reason));
    }

    public List<Violation> violations() {
        return violations;
    }

    public record Violation(String field, String reason) {
    }

    public ErrorCode error() {
        return error;
    }
}
