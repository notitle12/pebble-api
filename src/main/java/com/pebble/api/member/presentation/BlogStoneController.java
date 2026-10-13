package com.pebble.api.member.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.application.BlogStoneService;
import com.pebble.api.member.presentation.dto.BlogStoneRequest;
import com.pebble.api.post.application.PostQueryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class BlogStoneController {
    private final BlogStoneService stones;
    private final ObjectMapper mapper;
    @GetMapping("/api/v1/blogs/{handle}/stone-garden")
    public ApiResponse<BlogStoneService.Garden> publicGarden(@PathVariable String handle,HttpServletRequest request,@RequestBody(required=false) String body) {
        noPayload(request,body);
        if(!handle.matches("^[a-z][a-z0-9_-]{1,28}[a-z0-9_]$"))throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        return ApiResponse.of(stones.publicGarden(handle));
    }
    @GetMapping("/api/v1/members/me/stone-garden")
    public ApiResponse<BlogStoneService.Garden> mineGarden(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request,@RequestBody(required=false) String body) { noPayload(request,body);return ApiResponse.of(stones.mineGarden(member(jwt))); }
    @GetMapping("/api/v1/blogs/{handle}/stones")
    public ApiResponse<BlogStoneService.View> publicView(@PathVariable String handle,HttpServletRequest request,@RequestBody(required=false) String body) {
        noPayload(request,body);
        if(!handle.matches("^[a-z][a-z0-9_-]{1,28}[a-z0-9_]$"))throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        return ApiResponse.of(stones.publicView(handle));
    }
    @GetMapping("/api/v1/members/me/blog-stones")
    public ApiResponse<BlogStoneService.View> mine(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request,@RequestBody(required=false) String body) { noPayload(request,body);return ApiResponse.of(stones.mine(member(jwt))); }
    @GetMapping("/api/v1/members/me/blog-stones/posts")
    public ApiResponse<PostQueryService.StonePostPage> inventory(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") String page,HttpServletRequest request,@RequestBody(required=false) String body) {
        if(body!=null || request.getParameterMap().keySet().stream().anyMatch(key->!key.equals("page")) || request.getParameterValues("page")!=null && request.getParameterValues("page").length!=1 || !page.matches("0|[1-9][0-9]{0,5}"))throw MediaRequest.invalid();
        return ApiResponse.of(stones.inventory(member(jwt),Integer.parseInt(page)));
    }
    @PutMapping(value="/api/v1/members/me/blog-stones",consumes="application/json")
    public ApiResponse<BlogStoneService.View> save(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request,@RequestBody String body) {
        MediaRequest.noQuery(request);if(body.length()>150000)throw MediaRequest.invalid();
        try { var json=mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);return ApiResponse.of(stones.save(member(jwt),BlogStoneRequest.parse(json))); }
        catch(java.io.IOException exception) { throw MediaRequest.invalid(); }
    }
    private static long member(Jwt jwt) { return Long.parseLong(jwt.getSubject().substring("member:".length())); }
    private static void noPayload(HttpServletRequest request,String body) { MediaRequest.noQuery(request);if(body!=null)throw MediaRequest.invalid(); }
}
