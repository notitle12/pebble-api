package com.pebble.api.member.presentation.dto;

import com.pebble.api.member.domain.Member;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

public record PublicBlogSearchResponse(List<Blog> content, int page, int size, long totalElements,
                                       int totalPages, boolean hasNext, boolean hasPrevious) {
    public record Blog(String handle, String nickname, String blogName, String profileImageUrl) {}

    public static PublicBlogSearchResponse from(Page<Member> result, Function<Member, String> profileImage) {
        var content = result.getContent().stream()
                .map(member -> new Blog(member.getHandle(), member.getNickname(), member.getBlogName(), profileImage.apply(member)))
                .toList();
        return new PublicBlogSearchResponse(content, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.hasNext(), result.hasPrevious());
    }
}
