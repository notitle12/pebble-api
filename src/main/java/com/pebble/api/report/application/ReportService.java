package com.pebble.api.report.application;
import com.pebble.api.report.domain.*;
import com.pebble.api.report.infrastructure.persistence.ReportRepository;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.post.application.PostQueryService;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class ReportService {
    private final ReportRepository reports;
    private final MemberQueryService members;
    private final PostQueryService posts;
    private final com.pebble.api.project.application.ProjectQueryService projects;
    private final com.pebble.api.comment.application.CommentService comments;
    @Transactional
    public ContentReport create(long reporterId,ReportTarget targetType,long contentId,Long commentId,ReportReason reason,String detail) {
        members.findActiveForWrite(reporterId);
        String title;
        if(targetType.isPost()) {
            var post=posts.findForComments(contentId,true);
            if(post.getVisibility()!=PostVisibility.PUBLIC || post.isBlocked() || post.isDraft()
                || post.getAuthor().getStatus()==MemberStatus.WITHDRAWAL_PENDING) throw missing();
            title=post.getTitle();
        } else {
            var project=projects.findForComments(contentId,true);
            if(project.getVisibility()!=com.pebble.api.project.domain.ProjectVisibility.PUBLIC || project.isBlocked()
                || project.getOwner().getStatus()==MemberStatus.WITHDRAWAL_PENDING) throw missing();
            title=project.getName();
        }
        long targetId=contentId;
        Long parentId=null;
        if(targetType.isComment()) {
            if(commentId==null)throw missing();
            var view=comments.detail(targetType.isPost()?com.pebble.api.comment.domain.CommentTarget.POST:
                com.pebble.api.comment.domain.CommentTarget.PROJECT,contentId,commentId,reporterId);
            title=view.comment().getBody();
            int count=title.codePointCount(0,title.length());
            if(count>200)title=title.substring(0,title.offsetByCodePoints(0,200));
            targetId=commentId;parentId=contentId;
        }
        var existing=reports.findByTargetTypeAndTargetIdAndReporterId(targetType,targetId,reporterId);
        if(existing.isPresent())return existing.get();
        return reports.saveAndFlush(new ContentReport(targetType,targetId,parentId,title,reporterId,reason,detail));
    }
    public Page<ContentReport> listForManagement(ReportStatus status,ReportTarget type,Pageable page) {return reports.findForManagement(status,type,page);}
    public ContentReport findForManagement(long id){return reports.findById(id).orElseThrow(ReportService::missing);}
    @Transactional public ContentReport lockForManagement(long id){return reports.findForUpdate(id).orElseThrow(ReportService::missing);}
    @Transactional public ContentReport complete(ContentReport report,ReportAction action,long actorId,java.time.Instant now){
        report.resolve(action,actorId,now);reports.flush();return report;
    }
    private static ApplicationException missing(){return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);}
}
