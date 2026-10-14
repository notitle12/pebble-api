package com.pebble.api.comment.application;

import com.pebble.api.comment.domain.CommentVisibility;

public record CommentChanges(String body, CommentVisibility visibility, boolean hasBody, boolean hasVisibility) { }
