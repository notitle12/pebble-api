package com.pebble.api.report.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.admin.domain.*;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.AdminSessionService;
import com.pebble.api.member.domain.*;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.domain.*;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostReportIntegrationTest extends AuthenticationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired com.pebble.api.project.infrastructure.persistence.ProjectRepository projects;
    @Autowired com.pebble.api.comment.infrastructure.persistence.PostCommentRepository postComments;
    @Autowired com.pebble.api.comment.infrastructure.persistence.ProjectCommentRepository projectComments;
    @Autowired AdminAccountRepository accounts;
    @Autowired AdminSessionService sessions;
    @Autowired AccessTokenService tokens;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    Member owner,reporter;
    Post post;
    String user,operator;
    AdminAccount manager;
    @BeforeEach void setup(){
        owner=members.saveAndFlush(agreedMember(new Member("owner",null,MemberStatus.ACTIVE,null,null)));
        reporter=members.saveAndFlush(agreedMember(new Member("reporter",null,MemberStatus.ACTIVE,null,null)));
        post=posts.saveAndFlush(new Post(owner,null,"reported title",null,PostVisibility.PUBLIC,"report-"+UUID.randomUUID(),1));
        user="Bearer "+tokens.issueForMember(reporter.getId(),Instant.now());
        String password="Report-Test-Password-2026";
        manager=accounts.saveAndFlush(new AdminAccount("report-"+UUID.randomUUID(),passwords.encode(password),AdminRole.MANAGER));
        operator="Bearer "+sessions.login(manager.getLoginId(),password).tokens().accessToken();
    }
    private String path(){return "/api/v1/posts/"+post.getId()+"/reports";}
    private String submit() throws Exception{
        var result=mvc.perform(post(path()).header("Authorization",user).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"PRIVACY\",\"detail\":\"Please check\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN")).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }
    private org.springframework.test.web.servlet.ResultActions resolve(String id,String action) throws Exception{
        return mvc.perform(post("/api/v1/admin/reports/"+id+"/resolve").header("Authorization",operator)
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\""+action+"\"}"));
    }
    @Test void recordsAuthenticatedReporterAndDoesNotDuplicateOrLeakAdminFields() throws Exception{
        String id=submit();
        mvc.perform(post(path()).header("Authorization",user).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\",\"detail\":\"changed\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.reporterId").doesNotExist()).andExpect(jsonPath("$.data.detail").doesNotExist());
        assertThat(jdbc.queryForObject("select count(*) from content_report where post_id=?",Long.class,post.getId())).isEqualTo(1);
        mvc.perform(get("/api/v1/admin/reports").header("Authorization",operator).param("status","OPEN"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].reporterId").value(reporter.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].targetId").value(post.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].targetTitle").value("reported title"))
            .andExpect(jsonPath("$.data.content[0].reason").value("PRIVACY"));
    }
    @Test void reportEndpointsRequireUserAndAdminEndpointsRequireOperator() throws Exception{
        // Bearer 없는 변경 요청은 기존 CSRF 보호가 먼저 거부한다.
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}")).andExpect(status().isForbidden());
        mvc.perform(post(path()).header("Authorization",operator).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/reports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/reports").header("Authorization",user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reports/1/resolve").header("Authorization",user).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"DELETE\"}")).andExpect(status().isForbidden());
    }
    @Test void validatesReasonDetailAndForgedIdentity() throws Exception{
        for(String body:new String[]{"{}","{\"reason\":\"WRONG\"}","{\"reason\":\"OTHER\",\"detail\":\"  \"}",
            "{\"reason\":\"SPAM\",\"detail\":null}","{\"reason\":\"SPAM\",\"reporterId\":\"1\"}",
            "{\"reason\":\"SPAM\",\"detail\":\""+"a".repeat(1001)+"\"}"}){
            mvc.perform(post(path()).header("Authorization",user).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(path()+"?reporterId=1").header("Authorization",user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from content_report",Long.class)).isZero();
    }
    @Test void hiddenAndBlockedPostsCannotReceiveReports() throws Exception{
        post.changeVisibility(PostVisibility.HIDDEN);posts.flush();
        mvc.perform(post(path()).header("Authorization",user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}")).andExpect(status().isNotFound());
        post.changeVisibility(PostVisibility.PUBLIC);post.setBlocked(true,manager.getId());posts.flush();
        mvc.perform(post(path()).header("Authorization",user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}")).andExpect(status().isNotFound());
    }
    @Test void blockRecordsActionAndIsIdempotentButRejectsConflictingResolution() throws Exception{
        String id=submit();
        resolve(id,"BLOCK").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESOLVED"))
            .andExpect(jsonPath("$.data.action").value("BLOCK")).andExpect(jsonPath("$.data.handledByAdminId").value(manager.getId().toString()));
        assertThat(jdbc.queryForObject("select is_blocked from post where id=?",Boolean.class,post.getId())).isTrue();
        resolve(id,"BLOCK").andExpect(status().isOk());
        resolve(id,"DELETE").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select visibility_status from post where id=?",String.class,post.getId())).isEqualTo("PUBLIC");
    }
    @Test void deleteUsesExistingContentDeleteAndRetainsReport() throws Exception{
        String id=submit();resolve(id,"DELETE").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select visibility_status from post where id=?",String.class,post.getId())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select status from content_report where id=?",String.class,Long.valueOf(id))).isEqualTo("RESOLVED");
        resolve(id,"DELETE").andExpect(status().isOk());
    }
    @Test void dismissDoesNotAlterContentAndInvalidFiltersAreRejected() throws Exception{
        String id=submit();resolve(id,"DISMISS").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select is_blocked from post where id=?",Boolean.class,post.getId())).isFalse();
        mvc.perform(get("/api/v1/admin/reports").header("Authorization",operator).param("status","RESOLVED")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        for(String bad:new String[]{"WRONG",""})mvc.perform(get("/api/v1/admin/reports").header("Authorization",operator).param("status",bad)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/reports").header("Authorization",operator).param("q","secret")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/reports").header("Authorization",operator).param("page","-1")).andExpect(status().isBadRequest());
    }
    @Test void pendingAgreementCannotReport() throws Exception{
        var pending=members.saveAndFlush(new Member("pending",null,MemberStatus.ACTIVE,null,null));
        String pendingToken="Bearer "+tokens.issueForMember(pending.getId(),Instant.now());
        mvc.perform(post(path()).header("Authorization",pendingToken).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}")).andExpect(status().isForbidden());
    }
    @Test void separateReportersHaveSeparateRecordsAndWithdrawalRemovesReporterLink() throws Exception{
        submit();
        String ownerToken="Bearer "+tokens.issueForMember(owner.getId(),Instant.now());
        mvc.perform(post(path()).header("Authorization",ownerToken).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"OTHER\",\"detail\":\"  note  \"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from content_report where post_id=?",Long.class,post.getId())).isEqualTo(2);
        jdbc.update("delete from member where id=?",reporter.getId());
        assertThat(jdbc.queryForObject("select count(*) from content_report where post_id=? and reporter_member_id is null",Long.class,post.getId())).isEqualTo(1);
    }
    @Test void failedContentActionDoesNotMarkReportResolved() throws Exception{
        String id=submit();
        post.delete();posts.flush();
        resolve(id,"BLOCK").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select status from content_report where id=?",String.class,Long.valueOf(id))).isEqualTo("OPEN");
    }

    private com.pebble.api.project.domain.Project project() {
        return projects.saveAndFlush(new com.pebble.api.project.domain.Project(owner,"reported project",null,null,null,null,
            com.pebble.api.project.domain.ProjectLifecycleStatus.IN_PROGRESS,null,null,com.pebble.api.project.domain.ProjectVisibility.PUBLIC));
    }
    private String submitAt(String route) throws Exception {
        var result=mvc.perform(post(route).header("Authorization",user).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}")).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }
    @Test void projectCanBeBlockedDeletedAndResolvedIdempotently() throws Exception {
        var project=project();
        String route="/api/v1/projects/"+project.getId()+"/reports";
        String id=submitAt(route);
        resolve(id,"BLOCK").andExpect(status().isOk()).andExpect(jsonPath("$.data.targetType").value("PROJECT"));
        assertThat(jdbc.queryForObject("select is_blocked from project where id=?",Boolean.class,project.getId())).isTrue();
        var another=project();
        String deleted=submitAt("/api/v1/projects/"+another.getId()+"/reports");
        resolve(deleted,"DELETE").andExpect(status().isOk());
        resolve(deleted,"DELETE").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select visibility_status from project where id=?",String.class,another.getId())).isEqualTo("DELETED");
    }
    @Test void bothCommentTypesSupportReportingDeletionAndTypeFiltering() throws Exception {
        var project=project();
        var pc=postComments.saveAndFlush(new com.pebble.api.comment.domain.PostComment(post.getId(),owner,"post body",com.pebble.api.comment.domain.CommentVisibility.PUBLIC));
        var jc=projectComments.saveAndFlush(new com.pebble.api.comment.domain.ProjectComment(project.getId(),owner,"project body",com.pebble.api.comment.domain.CommentVisibility.PUBLIC));
        String first=submitAt("/api/v1/posts/"+post.getId()+"/comments/"+pc.getId()+"/reports");
        String second=submitAt("/api/v1/projects/"+project.getId()+"/comments/"+jc.getId()+"/reports");
        resolve(first,"BLOCK").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select status from content_report where id=?",String.class,Long.valueOf(first))).isEqualTo("OPEN");
        resolve(first,"DELETE").andExpect(status().isOk());
        resolve(first,"DELETE").andExpect(status().isOk());
        resolve(second,"DELETE").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select body from post_comment where id=?",String.class,pc.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select body from project_comment where id=?",String.class,jc.getId())).isEmpty();
        mvc.perform(get("/api/v1/admin/reports").header("Authorization",operator).param("targetType","PROJECT_COMMENT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].parentId").value(project.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].targetTitle").value("project body"));
        mvc.perform(get("/api/v1/admin/reports").header("Authorization",operator).param("targetType","COMMENT"))
            .andExpect(status().isBadRequest());
    }
    @Test void secretCommentsRequireReadPermissionAndMatchingParent() throws Exception {
        var secret=postComments.saveAndFlush(new com.pebble.api.comment.domain.PostComment(post.getId(),owner,"secret body",com.pebble.api.comment.domain.CommentVisibility.SECRET));
        String route="/api/v1/posts/"+post.getId()+"/comments/"+secret.getId()+"/reports";
        mvc.perform(post(route).header("Authorization",user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
            .andExpect(status().isNotFound());
        var another=posts.saveAndFlush(new Post(owner,null,"other",null,PostVisibility.PUBLIC,"other-"+UUID.randomUUID(),2));
        mvc.perform(post("/api/v1/posts/"+another.getId()+"/comments/"+secret.getId()+"/reports")
            .header("Authorization","Bearer "+tokens.issueForMember(owner.getId(),Instant.now()))
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(post(route).header("Authorization","Bearer "+tokens.issueForMember(owner.getId(),Instant.now()))
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
            .andExpect(status().isOk());
    }

}
