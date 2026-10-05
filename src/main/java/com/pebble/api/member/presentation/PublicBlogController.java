package com.pebble.api.member.presentation;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.presentation.dto.PublicBlogResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicBlogController {
    private final MemberQueryService members;
    private final com.pebble.api.member.application.MemberProfileImages images;

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
}
