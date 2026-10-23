package com.pebble.api.insight.presentation;

import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.insight.application.ContentInsightsService;
import com.pebble.api.insight.application.ContentInsightsService.Kind;
import com.pebble.api.member.application.BlogVisitLimiter;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ContentInsightsController {
    public static final String VISITOR_COOKIE="pebble_content_visitor";
    private final ContentInsightsService insights;
    private final BlogVisitLimiter limiter;
    private final Clock clock;
    private final Environment environment;

    @PostMapping("/api/v1/content-views/{kind:posts|projects}/{id:[0-9]+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@PathVariable String kind,@PathVariable String id,@AuthenticationPrincipal Jwt jwt,
                       HttpServletRequest request,HttpServletResponse response,@RequestBody(required=false) String body) {
        MediaRequest.noQuery(request);
        if(body!=null||!request.getParameterMap().isEmpty()) throw MediaRequest.invalid();
        long target=positiveId(id);
        limiter.check(request.getRemoteAddr());
        String visitor=null;
        int count=0;
        if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(VISITOR_COOKIE.equals(cookie.getName())) {
            visitor=cookie.getValue(); count++;
        }
        if(count>1) throw MediaRequest.invalid();
        if(visitor==null||!visitor.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")) visitor=UUID.randomUUID().toString();
        Long memberId=jwt!=null&&jwt.getSubject().startsWith("member:")?positiveId(jwt.getSubject().substring(7)):null;
        if(insights.record(kind.equals("posts")?Kind.POST:Kind.PROJECT,target,visitor,memberId)) {
            response.addHeader(HttpHeaders.SET_COOKIE,dailyVisitorCookie(visitor,clock.instant(),request.isSecure()||environment.matchesProfiles("prod")).toString());
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
    }

    @GetMapping("/api/v1/members/me/content-insights")
    public ApiResponse<ContentInsightsService.Insights> read(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request,
        HttpServletResponse response,@RequestBody(required=false) String body) {
        if(body!=null) throw MediaRequest.invalid();
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!e.getKey().equals("days")||e.getValue().length!=1)) throw MediaRequest.invalid();
        String raw=request.getParameter("days");
        if(raw!=null&&!Set.of("0","7","30","90").contains(raw)) throw MediaRequest.invalid();
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return ApiResponse.of(insights.read(positiveId(jwt.getSubject().substring(7)),raw==null?30:Integer.parseInt(raw)));
    }

    static ResponseCookie dailyVisitorCookie(String value,Instant now,boolean secure) {
        Instant midnight=now.atZone(ContentInsightsService.SEOUL).toLocalDate().plusDays(1).atStartOfDay(ContentInsightsService.SEOUL).toInstant();
        return ResponseCookie.from(VISITOR_COOKIE,value).path("/api/v1/content-views").httpOnly(true).secure(secure).sameSite("Lax")
            .maxAge(Math.max(1,Duration.between(now,midnight).getSeconds())).build();
    }
    private static long positiveId(String value) {
        try {
            if(!value.matches("[1-9][0-9]{0,18}")) throw MediaRequest.invalid();
            return Long.parseLong(value);
        } catch(NumberFormatException exception) { throw MediaRequest.invalid(); }
    }
}
