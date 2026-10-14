package com.pebble.api.member.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.application.BlogLinksService;
import com.pebble.api.member.application.BlogVisitsService;
import com.pebble.api.member.application.BlogVisitLimiter;
import com.pebble.api.member.presentation.dto.BlogLinksRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequiredArgsConstructor
public class BlogToolsController {
    public static final String VISITOR_COOKIE="pebble_blog_visitor";
    private final BlogLinksService links;
    private final BlogVisitsService visits;
    private final BlogVisitLimiter limiter;
    private final ObjectMapper mapper;
    private final Environment environment;

    @GetMapping("/api/v1/blogs/{handle}/links")
    public ApiResponse<BlogLinksService.Links> links(@PathVariable String handle,HttpServletRequest request,@RequestBody(required=false) String body){noBody(request,body);checkHandle(handle);return ApiResponse.of(links.publicLinks(handle));}
    @GetMapping("/api/v1/members/me/blog-links")
    public ApiResponse<BlogLinksService.Links> mine(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request,@RequestBody(required=false) String body){noBody(request,body);return ApiResponse.of(links.mine(memberId(jwt)));}
    @PutMapping(value="/api/v1/members/me/blog-links",consumes="application/json")
    public ApiResponse<BlogLinksService.Links> save(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body,HttpServletRequest request){MediaRequest.noQuery(request);return ApiResponse.of(links.save(memberId(jwt),BlogLinksRequest.parse(body),Map.of()));}
    @PutMapping(value="/api/v1/members/me/blog-links",consumes="multipart/form-data")
    public ApiResponse<BlogLinksService.Links> saveFiles(@AuthenticationPrincipal Jwt jwt,MultipartHttpServletRequest request){
        MediaRequest.noQuery(request);
        if(request.getParameterMap().size()!=1||request.getParameterValues("links")==null||request.getParameterValues("links").length!=1)throw MediaRequest.invalid();
        String json=request.getParameter("links");if(json.length()>20000)throw MediaRequest.invalid();
        try {
            var input=BlogLinksRequest.parse(mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json));
            Map<Integer,byte[]> logos=new HashMap<>();
            for(var entry:request.getMultiFileMap().entrySet()){
                if(!entry.getKey().matches("logo[0-4]")||entry.getValue().size()!=1)throw MediaRequest.invalid();
                var file=entry.getValue().getFirst();if(file.isEmpty())throw MediaRequest.invalid();
                if(file.getSize()>2*1024*1024)throw new ApplicationException(GlobalErrorCode.MEDIA_TOO_LARGE);
                logos.put(Integer.parseInt(entry.getKey().substring(4)),file.getBytes());
            }
            return ApiResponse.of(links.save(memberId(jwt),input,logos));
        }catch(java.io.IOException exception){throw MediaRequest.invalid();}
    }
    @GetMapping("/api/v1/blogs/{handle}/visits")
    public ApiResponse<BlogVisitsService.Stats> stats(@PathVariable String handle,HttpServletRequest request,@RequestBody(required=false) String body){noBody(request,body);checkHandle(handle);return ApiResponse.of(visits.read(handle));}
    @PostMapping("/api/v1/blogs/{handle}/visits")
    public ApiResponse<BlogVisitsService.Stats> record(@PathVariable String handle,HttpServletRequest request,HttpServletResponse response,@RequestBody(required=false) String body){
        noBody(request,body);checkHandle(handle);limiter.check(request.getRemoteAddr());
        String visitor=null;int found=0;
        if(request.getCookies()!=null)for(var cookie:request.getCookies())if(VISITOR_COOKIE.equals(cookie.getName())){visitor=cookie.getValue();found++;}
        if(found>1)throw MediaRequest.invalid();
        boolean fresh=visitor==null||!visitor.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}");
        if(fresh)visitor=UUID.randomUUID().toString();
        var result=visits.record(handle,visitor);
        if(fresh)response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(VISITOR_COOKIE,visitor).httpOnly(true).secure(request.isSecure()||environment.matchesProfiles("prod")).sameSite("Lax").path("/api/v1/blogs").maxAge(java.time.Duration.ofDays(180)).build().toString());
        return ApiResponse.of(result);
    }
    private static long memberId(Jwt jwt){return Long.parseLong(jwt.getSubject().substring("member:".length()));}
    private static void noBody(HttpServletRequest request,String body){MediaRequest.noQuery(request);if(body!=null)throw MediaRequest.invalid();}
    private static void checkHandle(String handle){if(!handle.matches("^[a-z][a-z0-9_-]{1,28}[a-z0-9_]$"))throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);}
}
