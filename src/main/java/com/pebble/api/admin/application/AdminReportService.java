package com.pebble.api.admin.application;
import com.pebble.api.admin.domain.*;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.report.application.ReportService;
import com.pebble.api.report.domain.*;
import com.pebble.api.post.application.PostService;
import com.pebble.api.global.exception.*;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
@Transactional
public class AdminReportService {
    private final AdminAccountRepository accounts;
    private final ReportService reports;
    private final PostService posts;
    private final com.pebble.api.project.application.ProjectService projects;
    private final com.pebble.api.comment.application.CommentService comments;
    private final Clock clock;
    public Page<ContentReport> list(long actorId,ReportStatus status,ReportTarget type,Pageable page){requireOperator(actorId);return reports.listForManagement(status,type,page);}
    public ContentReport resolve(long actorId,long reportId,ReportAction action){
        requireOperator(actorId);
        var target=reports.findForManagement(reportId);
        var type=target.getTargetType();
        if(type.isComment()&&action==ReportAction.BLOCK)throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        // 관리자→콘텐츠 소유자→콘텐츠→댓글(해당 시)→신고 순서로 기존 쓰기와 직렬화한다.
        long contentId=target.getContainerId();
        if(type.isPost())posts.lockForReportManagement(contentId);else projects.lockForReportManagement(contentId);
        if(type.isComment())comments.lockForReportManagement(type.isPost()?com.pebble.api.comment.domain.CommentTarget.POST:
            com.pebble.api.comment.domain.CommentTarget.PROJECT,target.getTargetId());
        var report=reports.lockForManagement(reportId);
        if(report.getStatus()==ReportStatus.RESOLVED){
            if(report.getAction()!=action)throw new ApplicationException(ReportError.REPORT_ALREADY_HANDLED);
            return report;
        }
        if(type.isComment()) {
            if(action==ReportAction.DELETE)comments.deleteForManagement(type.isPost()?com.pebble.api.comment.domain.CommentTarget.POST:
                com.pebble.api.comment.domain.CommentTarget.PROJECT,target.getTargetId());
        } else if(type.isPost()) {
            if(action==ReportAction.BLOCK)posts.setBlockedForManagement(contentId,actorId,true);
            else if(action==ReportAction.DELETE)posts.deleteForManagement(contentId);
        } else {
            if(action==ReportAction.BLOCK)projects.setBlockedForManagement(contentId,actorId,true);
            else if(action==ReportAction.DELETE)projects.deleteForManagement(contentId);
        }
        return reports.complete(report,action,actorId,clock.instant());
    }
    private void requireOperator(long id){
        var actor=accounts.findForAuthenticationById(id).orElseThrow(()->new ApplicationException(GlobalErrorCode.INVALID_TOKEN));
        if(actor.getStatus()!=AdminStatus.ACTIVE)throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
    }
}
