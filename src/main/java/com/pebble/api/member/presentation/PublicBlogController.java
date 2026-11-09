package com.pebble.api.member.presentation;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.presentation.dto.PublicBlogResponse;
import com.pebble.api.member.presentation.dto.PublicBlogSearchResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicBlogController {
    private final MemberQueryService members;
    private final com.pebble.api.member.application.MemberProfileImages images;

    @GetMapping("/api/v1/blogs")
    public ApiResponse<PublicBlogSearchResponse> search(@RequestParam MultiValueMap<String, String> query,
                                                      @RequestBody(required = false) String body) {
        if (body != null) throw invalid("body");
        for (var entry : query.entrySet()) {
            if (!Set.of("q", "page", "size").contains(entry.getKey()) || entry.getValue().size() != 1) {
                throw invalid(entry.getKey());
            }
        }
        String term = query.getFirst("q");
        if (term == null) throw invalid("q");
        term = term.strip();
        if (term.isEmpty() || term.codePointCount(0, term.length()) > 200
                || term.codePoints().anyMatch(c -> c == 0 || c >= 0xD800 && c <= 0xDFFF)) throw invalid("q");
        int page = integer(query.getFirst("page"), 0, "page");
        int size = integer(query.getFirst("size"), 20, "size");
        if (size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw invalid("page/size");
        var result = members.searchPublicBlogs(term, PageRequest.of(page, size, Sort.by("handle")));
        return ApiResponse.of(PublicBlogSearchResponse.from(result, images::url));
    }

    @GetMapping("/api/v1/blogs/{handle}")
    public ApiResponse<PublicBlogResponse> profile(@PathVariable String handle, HttpServletRequest request,
                                                  @RequestBody(required = false) String body) {
        if (request.getQueryString() != null || body != null) {
            throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR);
        }
        if (!handle.matches("^[a-z][a-z0-9_-]{1,28}[a-z0-9_]$")) {
            throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        }
        var member = members.findPublicBlog(handle);
        return ApiResponse.of(PublicBlogResponse.from(member, images.url(member)));
    }

    private static int integer(String value, int fallback, String field) {
        if (value == null) return fallback;
        if (!value.matches("[0-9]+")) throw invalid(field);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid(field);
        }
    }

    private static ApplicationException invalid(String field) {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, "지원되는 요청 값을 입력해 주세요.");
    }
}
