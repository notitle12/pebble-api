package com.pebble.api.report.presentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.report.application.ReportService;
import com.pebble.api.global.presentation.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/{resource:posts|projects}/{contentId:[0-9]+}")
public class ReportController {
    private final ReportService reports;
    @PostMapping(value="/reports",consumes="application/json")
    public ApiResponse<Receipt> create(@PathVariable String resource,@PathVariable String contentId,@AuthenticationPrincipal Jwt jwt,
            @RequestBody JsonNode body,HttpServletRequest request){
        return submit(resource,contentId,null,jwt,body,request);
    }
    @PostMapping(value="/comments/{commentId:[0-9]+}/reports",consumes="application/json")
    public ApiResponse<Receipt> comment(@PathVariable String resource,@PathVariable String contentId,@PathVariable String commentId,
            @AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body,HttpServletRequest request){
        return submit(resource,contentId,ReportInput.id(commentId),jwt,body,request);
    }
    private ApiResponse<Receipt> submit(String resource,String contentId,Long commentId,Jwt jwt,JsonNode body,HttpServletRequest request){
        if(request.getQueryString()!=null)throw ReportInput.invalid();
        var input=ReportInput.submission(body);
        boolean post=resource.equals("posts");
        var type=commentId==null?(post?com.pebble.api.report.domain.ReportTarget.POST:com.pebble.api.report.domain.ReportTarget.PROJECT):
            (post?com.pebble.api.report.domain.ReportTarget.POST_COMMENT:com.pebble.api.report.domain.ReportTarget.PROJECT_COMMENT);
        var report=reports.create(ReportInput.id(jwt.getSubject().substring("member:".length())),type,ReportInput.id(contentId),commentId,input.reason(),input.detail());
        return ApiResponse.of(new Receipt(report.getId().toString(),report.getStatus().name()));
    }
    public record Receipt(String id,String status) { }
}
