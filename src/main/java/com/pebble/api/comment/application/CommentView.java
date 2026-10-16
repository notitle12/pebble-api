package com.pebble.api.comment.application;

import com.pebble.api.comment.domain.Comment;

/** 댓글과 현재 열람자에게 본문을 가릴지 여부를 함께 전달한다. */
public record CommentView(Comment comment, boolean locked) { }
