package com.pebble.api.report.domain;

import com.pebble.api.global.persistence.BaseCreatedEntity;
import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "content_report")
public class ContentReport extends BaseCreatedEntity {
    @Id private Long id;
    @Enumerated(EnumType.STRING) @Column(name="target_type",nullable=false,length=20,updatable=false) private ReportTarget targetType;
    @Column(name="target_id",nullable=false,updatable=false) private Long targetId;
    @Column(name="target_title",nullable=false,length=200,updatable=false) private String targetTitle;
    @Column(name="post_id",updatable=false) private Long postId;
    @Column(name="project_id",updatable=false) private Long projectId;
    @Column(name="post_comment_id",updatable=false) private Long postCommentId;
    @Column(name="project_comment_id",updatable=false) private Long projectCommentId;
    @Column(name="reporter_member_id",updatable=false) private Long reporterId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20,updatable=false) private ReportReason reason;
    @Column(nullable=false,length=1000,updatable=false) private String detail;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private ReportStatus status;
    @Enumerated(EnumType.STRING) @Column(length=20) private ReportAction action;
    @Column(name="handled_by_admin_id") private Long handledByAdminId;
    @Column(name="handled_at") private Instant handledAt;
    protected ContentReport() { }
    public ContentReport(ReportTarget type,long targetId,Long parentId,String title,long reporterId,ReportReason reason,String detail) {
        this.targetType=type;this.targetId=targetId;this.targetTitle=title;
        this.postId=type==ReportTarget.POST?Long.valueOf(targetId):type==ReportTarget.POST_COMMENT?parentId:null;
        this.projectId=type==ReportTarget.PROJECT?Long.valueOf(targetId):type==ReportTarget.PROJECT_COMMENT?parentId:null;
        this.postCommentId=type==ReportTarget.POST_COMMENT?Long.valueOf(targetId):null;
        this.projectCommentId=type==ReportTarget.PROJECT_COMMENT?Long.valueOf(targetId):null;
        this.reporterId=reporterId;this.reason=reason;this.detail=detail;this.status=ReportStatus.OPEN;
    }
    @PrePersist void identify() { id=TsidGenerator.generate(); }
    public void resolve(ReportAction action,long actorId,Instant now) {
        this.status=ReportStatus.RESOLVED;this.action=action;this.handledByAdminId=actorId;this.handledAt=now;
    }
    public Long getId(){return id;}
    public ReportTarget getTargetType(){return targetType;}
    public Long getTargetId(){return targetId;}
    public String getTargetTitle(){return targetTitle;}
    public Long getParentId(){return targetType.isComment()?(targetType.isPost()?postId:projectId):null;}
    public long getContainerId(){return targetType.isPost()?postId:projectId;}
    public Long getReporterId(){return reporterId;}
    public ReportReason getReason(){return reason;}
    public String getDetail(){return detail;}
    public ReportStatus getStatus(){return status;}
    public ReportAction getAction(){return action;}
    public Long getHandledByAdminId(){return handledByAdminId;}
    public Instant getHandledAt(){return handledAt;}
}
