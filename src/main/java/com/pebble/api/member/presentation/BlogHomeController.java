package com.pebble.api.member.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.application.BlogHomeService;
import com.pebble.api.member.presentation.dto.BlogHomeRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class BlogHomeController {
    private final BlogHomeService homes;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    @GetMapping("/api/v1/blogs/{handle}/home")
    public ApiResponse<BlogHomeService.Home> home(@PathVariable String handle, HttpServletRequest request,
                                                 @RequestBody(required=false) String body) {
        noPayload(request, body);
        if (!handle.matches("^[a-z][a-z0-9_-]{1,28}[a-z0-9_]$")) throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        return ApiResponse.of(homes.publicHome(handle));
    }

    @GetMapping("/api/v1/members/me/blog-home")
    public ApiResponse<BlogHomeService.Settings> mine(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                     @RequestBody(required=false) String body) {
        noPayload(request, body);
        return ApiResponse.of(homes.mine(memberId(jwt)));
    }

    @PutMapping(value="/api/v1/members/me/blog-home", consumes="application/json")
    public ApiResponse<BlogHomeService.Settings> save(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                     @RequestBody String body) {
        MediaRequest.noQuery(request);
        if (body.length() > 20000) throw MediaRequest.invalid();
        try {
            JsonNode json = mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            return ApiResponse.of(homes.save(memberId(jwt), BlogHomeRequest.parse(json)));
        } catch (java.io.IOException exception) { throw MediaRequest.invalid(); }
    }

    private static long memberId(Jwt jwt) { return Long.parseLong(jwt.getSubject().substring("member:".length())); }
    private static void noPayload(HttpServletRequest request, String body) {
        MediaRequest.noQuery(request);
        if (body != null) throw MediaRequest.invalid();
    }
}
