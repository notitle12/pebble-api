package com.pebble.api.admin.presentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.admin.application.AdminReportService;
import com.pebble.api.admin.infrastructure.AdminContentAudit;
import com.pebble.api.admin.presentation.dto.AdminContentPage;
import com.pebble.api.report.domain.*;
import com.pebble.api.report.presentation.ReportInput;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.presentation.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {
    private final AdminReportService reports;
    private final AdminContentAudit audit;
    @GetMapping
    public ApiResponse<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request){
        long actor=actor(jwt);
        return audited("REPORT_LIST",actor,null,request,()->{
            if(query.entrySet().stream().anyMatch(e->!Set.of("page","size","status","targetType").contains(e.getKey())||e.getValue().size()!=1))throw ReportInput.invalid();
            int page=number(query.getFirst("page"),0),size=number(query.getFirst("size"),20);
            if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)throw ReportInput.invalid();
            ReportStatus status=null;
            if(query.containsKey("status"))try{status=ReportStatus.valueOf(query.getFirst("status"));}catch(IllegalArgumentException e){throw ReportInput.invalid();}
            ReportTarget targetType=null;
            if(query.containsKey("targetType"))try{targetType=ReportTarget.valueOf(query.getFirst("targetType"));}catch(IllegalArgumentException e){throw ReportInput.invalid();}
            return ApiResponse.of(AdminContentPage.from(reports.list(actor,status,targetType,PageRequest.of(page,size,Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("id")))).map(View::from)));
        });
    }
    @PostMapping(value="/{reportId:[0-9]+}/resolve",consumes="application/json")
    public ApiResponse<?> resolve(@PathVariable String reportId,@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode body,HttpServletRequest request){
        long actor=actor(jwt),id=ReportInput.id(reportId);
        return audited("REPORT_RESOLVE",actor,id,request,()->{
            if(request.getQueryString()!=null)throw ReportInput.invalid();
            return ApiResponse.of(View.from(reports.resolve(actor,id,ReportInput.action(body))));
        });
    }
    private <T> T audited(String action,long actor,Long target,HttpServletRequest request,Supplier<T> run){
        try{T result=run.get();audit.record(action,"SUCCESS","report",actor,target,request);return result;}
        catch(ApplicationException e){audit.record(action,"REJECTED","report",actor,target,request);throw e;}
        catch(RuntimeException e){audit.record(action,"BACKEND_FAILURE","report",actor,target,request);throw e;}
    }
    private int number(String value,int fallback){if(value==null)return fallback;try{return Integer.parseInt(value);}catch(NumberFormatException e){throw ReportInput.invalid();}}
    private long actor(Jwt jwt){return ReportInput.id(jwt.getSubject().substring("admin:".length()));}
    public record View(String id,ReportTarget targetType,String targetId,String targetTitle,String parentId,String reporterId,ReportReason reason,String detail,
            ReportStatus status,ReportAction action,String handledByAdminId,Instant handledAt,Instant createdAt){
        static View from(ContentReport report){return new View(report.getId().toString(),report.getTargetType(),report.getTargetId().toString(),report.getTargetTitle(),
            report.getParentId()==null?null:report.getParentId().toString(),
            report.getReporterId()==null?null:report.getReporterId().toString(),report.getReason(),report.getDetail(),report.getStatus(),report.getAction(),
            report.getHandledByAdminId()==null?null:report.getHandledByAdminId().toString(),report.getHandledAt(),report.getCreatedAt());}
    }
}
