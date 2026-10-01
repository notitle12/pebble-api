package com.pebble.api.post.application;

import com.pebble.api.post.domain.BlockType;
import com.pebble.api.post.domain.CodeLanguage;
import com.pebble.api.post.domain.PostVisibility;
import java.util.List;
import java.util.Set;

/** 수정 시 생략과 명시적 null을 구분하는 입력이다. */
public record PostChanges(Set<String> supplied, String title, String summary, Long categoryId,
                          List<Long> tagIds, List<BlockInput> blocks, PostVisibility visibilityStatus, String slug,
                          Integer displayOrder, Long boardId) {
    public boolean has(String field) {
        return supplied.contains(field);
    }

    public record BlockInput(BlockType type, String content, CodeLanguage language, String title) {
    }
}
