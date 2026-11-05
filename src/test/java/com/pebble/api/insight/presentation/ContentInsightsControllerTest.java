package com.pebble.api.insight.presentation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.member.domain.*;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.domain.*;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.project.domain.*;
import com.pebble.api.project.infrastructure.persistence.ProjectRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContentInsightsControllerTest extends AuthenticationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired ProjectRepository projects;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    Member owner;
    Post post;
    Project project;
    String token;
    static final String ORIGIN="http://localhost:3000";
    static final String INSIGHTS="/api/v1/members/me/content-insights";
    @BeforeEach void setup() {
        String key=UUID.randomUUID().toString().substring(0,12);
        owner=agreedMember(new Member("c-"+key,null,MemberStatus.ACTIVE,null,null));
        owner.completeProfile("Blog "+key,"c-"+key,owner.getNickname(),Instant.now());
        members.saveAndFlush(owner);
        post=posts.saveAndFlush(new Post(owner,null,"Post",null,PostVisibility.PUBLIC,null,1));
        project=projects.saveAndFlush(new Project(owner,"Project",null,null,null,null,ProjectLifecycleStatus.IN_PROGRESS,null,null,ProjectVisibility.PUBLIC));
        token=tokens.issueForMember(owner.getId(),Instant.now());
    }
    String path() {return "/api/v1/content-views/posts/"+post.getId();}
    @Test void publicWriteUsesDailyHttpOnlyCookieAndOwnerReadIsPrivate() throws Exception {
        var response=mvc.perform(post(path()).header("Origin",ORIGIN)).andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        String cookie=response.getHeader("Set-Cookie");
        assertThat(cookie).contains("pebble_content_visitor=","Path=/api/v1/content-views","HttpOnly","SameSite=Lax").doesNotContain("Domain=");
        String value=cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';'));
        mvc.perform(post(path()).header("Origin",ORIGIN).cookie(new Cookie(ContentInsightsController.VISITOR_COOKIE,value))).andExpect(status().isNoContent());
        mvc.perform(get(INSIGHTS)).andExpect(status().isUnauthorized());
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.postViews").value(1)).andExpect(jsonPath("$.data.days").value(30)).andExpect(jsonPath("$.data.daily.length()").value(30))
            .andExpect(jsonPath("$.data.posts[0].id").value(post.getId().toString())).andExpect(jsonPath("$.data.posts[0].isBlocked").value(false))
            .andExpect(jsonPath("$.data.posts[0].urlKey").value("1"));
        mvc.perform(get("/api/v1/members/"+owner.getId()+"/content-insights").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void signedOwnerDoesNotCountOrReceiveCookie() throws Exception {
        mvc.perform(post(path()).header("Origin",ORIGIN).header("Authorization","Bearer "+token)).andExpect(status().isNoContent()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.postViews").value(0));
        mvc.perform(post("/api/v1/content-views/projects/"+project.getId()).header("Origin",ORIGIN)).andExpect(status().isNoContent());
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.projectViews").value(1));
    }
    @Test void originAndEndpointMethodsAreStrict() throws Exception {
        mvc.perform(post(path())).andExpect(status().isForbidden());
        mvc.perform(post(path()).header("Origin","https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(post(path()).header("Origin",ORIGIN,ORIGIN)).andExpect(status().isForbidden());
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(options(path()).header("Origin",ORIGIN).header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Credentials","true"));
        mvc.perform(options(path()).header("Origin",ORIGIN).header("Access-Control-Request-Method","PUT")).andExpect(status().isForbidden());
    }
    @Test void invalidBodiesQueriesIdsAndDuplicateCookiesAreRejected() throws Exception {
        mvc.perform(post(path()).header("Origin",ORIGIN).param("ownerId",owner.getId().toString())).andExpect(status().isBadRequest());
        mvc.perform(post(path()).header("Origin",ORIGIN).contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
        mvc.perform(post(path()).header("Origin",ORIGIN).cookie(new Cookie(ContentInsightsController.VISITOR_COOKIE,"a"),new Cookie(ContentInsightsController.VISITOR_COOKIE,"b"))).andExpect(status().isBadRequest());
        for(String id:new String[]{"0","01","9223372036854775808"}) mvc.perform(post("/api/v1/content-views/posts/"+id).header("Origin",ORIGIN)).andExpect(status().isBadRequest());
        for(String days:new String[]{"2","-1","007","014","91",""}) mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).param("days",days)).andExpect(status().isBadRequest());
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).param("days","7","30")).andExpect(status().isBadRequest());
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).param("memberId",owner.getId().toString())).andExpect(status().isBadRequest());
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).content("{}" )).andExpect(status().isBadRequest());
    }
    @Test void oneDayInsightsUseSeoulDateForOverviewAndBothDetailKinds() throws Exception {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate yesterday=today.minusDays(1);
        Instant seoulMidnight=today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        assertThat(seoulMidnight.atZone(ZoneOffset.UTC).toLocalDate()).isEqualTo(yesterday);

        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).param("days","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days").value(1))
                .andExpect(jsonPath("$.data.fromDate").value(today.toString())).andExpect(jsonPath("$.data.toDate").value(today.toString()))
                .andExpect(jsonPath("$.data.chartDays").value(1)).andExpect(jsonPath("$.data.daily.length()").value(1))
                .andExpect(jsonPath("$.data.daily[0].date").value(today.toString()))
                .andExpect(jsonPath("$.data.postViews").value(0)).andExpect(jsonPath("$.data.projectViews").value(0));
        for(String kind:new String[]{"posts","projects"}) {
            String id=kind.equals("posts")?post.getId().toString():project.getId().toString();
            mvc.perform(get(INSIGHTS+"/"+kind+"/"+id).header("Authorization","Bearer "+token).param("days","1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.days").value(1))
                    .andExpect(jsonPath("$.data.fromDate").value(today.toString())).andExpect(jsonPath("$.data.toDate").value(today.toString()))
                    .andExpect(jsonPath("$.data.chartDays").value(1)).andExpect(jsonPath("$.data.daily.length()").value(1))
                    .andExpect(jsonPath("$.data.daily[0].date").value(today.toString()))
                    .andExpect(jsonPath("$.data.item.views").value(0)).andExpect(jsonPath("$.data.sourceDays").value(7));
        }

        jdbc.update("insert into content_view_stats(kind,target_id,post_id,project_id,owner_member_id,views) values('POST',?,?,null,?,10)",
                post.getId(),post.getId(),owner.getId());
        jdbc.update("insert into content_view_day(kind,target_id,view_date,views) values('POST',?,?,?),( 'POST',?,?,?)",
                post.getId(),yesterday,6,post.getId(),today,4);
        jdbc.update("insert into content_view_stats(kind,target_id,post_id,project_id,owner_member_id,views) values('PROJECT',?,null,?,?,9)",
                project.getId(),project.getId(),owner.getId());
        jdbc.update("insert into content_view_day(kind,target_id,view_date,views) values('PROJECT',?,?,?),( 'PROJECT',?,?,?)",
                project.getId(),yesterday,7,project.getId(),today,2);
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).param("days","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.postViews").value(4)).andExpect(jsonPath("$.data.projectViews").value(2))
                .andExpect(jsonPath("$.data.daily.length()").value(1)).andExpect(jsonPath("$.data.daily[0].postViews").value(4))
                .andExpect(jsonPath("$.data.daily[0].projectViews").value(2));
        mvc.perform(get(INSIGHTS+"/posts/"+post.getId()).header("Authorization","Bearer "+token).param("days","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalViews").value(10)).andExpect(jsonPath("$.data.item.views").value(4))
                .andExpect(jsonPath("$.data.daily.length()").value(1)).andExpect(jsonPath("$.data.daily[0].views").value(4));
        mvc.perform(get(INSIGHTS+"/projects/"+project.getId()).header("Authorization","Bearer "+token).param("days","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalViews").value(9)).andExpect(jsonPath("$.data.item.views").value(2))
                .andExpect(jsonPath("$.data.daily.length()").value(1)).andExpect(jsonPath("$.data.daily[0].views").value(2));

        String key=UUID.randomUUID().toString().substring(0,12);
        Member another=agreedMember(new Member("x-"+key,null,MemberStatus.ACTIVE,null,null));
        another.completeProfile("Other "+key,"x-"+key,another.getNickname(),Instant.now());
        members.saveAndFlush(another);
        String otherToken=tokens.issueForMember(another.getId(),Instant.now());
        mvc.perform(get(INSIGHTS+"/posts/"+post.getId()).header("Authorization","Bearer "+otherToken).param("days","1"))
                .andExpect(status().isNotFound());
        mvc.perform(get(INSIGHTS+"/projects/"+project.getId()).header("Authorization","Bearer "+otherToken).param("days","1"))
                .andExpect(status().isNotFound());
    }
    @Test void oneDayReadUsesTheNewSeoulDayWhileUtcIsStillYesterday() {
        var clock=Clock.fixed(Instant.parse("2026-11-05T15:00:00Z"),ZoneOffset.UTC);
        var service=new com.pebble.api.insight.application.ContentInsightsService(clock,jdbc,members);
        jdbc.update("insert into content_view_stats(kind,target_id,post_id,project_id,owner_member_id,views) values('POST',?,?,null,?,10)",post.getId(),post.getId(),owner.getId());
        jdbc.update("insert into content_view_day(kind,target_id,view_date,views) values('POST',?,'2026-11-05',9),('POST',?,'2026-11-06',1)",post.getId(),post.getId());
        var overview=service.read(owner.getId(),1);
        assertThat(overview.fromDate()).isEqualTo("2026-11-06");
        assertThat(overview.toDate()).isEqualTo("2026-11-06");
        assertThat(overview.postViews()).isEqualTo(1);
        assertThat(overview.daily()).hasSize(1);
        assertThat(service.detail(owner.getId(),com.pebble.api.insight.application.ContentInsightsService.Kind.POST,post.getId(),1).item().views()).isEqualTo(1);
        assertThat(service.read(owner.getId(),7).postViews()).isEqualTo(10);
    }
    @Test void fourteenDayInsightsIncludeBothInclusiveEdgesButKeepCumulativeDetailTotals() throws Exception {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate from=today.minusDays(13);
        LocalDate excluded=today.minusDays(14);
        jdbc.update("insert into content_view_stats(kind,target_id,post_id,project_id,owner_member_id,views) values('POST',?,?,null,?,28)",
                post.getId(),post.getId(),owner.getId());
        jdbc.update("insert into content_view_day(kind,target_id,view_date,views) values('POST',?,?,?),( 'POST',?,?,?),( 'POST',?,?,?)",
                post.getId(),excluded,20,post.getId(),from,3,post.getId(),today,5);
        jdbc.update("insert into content_view_stats(kind,target_id,post_id,project_id,owner_member_id,views) values('PROJECT',?,null,?,?,17)",
                project.getId(),project.getId(),owner.getId());
        jdbc.update("insert into content_view_day(kind,target_id,view_date,views) values('PROJECT',?,?,?),( 'PROJECT',?,?,?),( 'PROJECT',?,?,?)",
                project.getId(),excluded,7,project.getId(),from,4,project.getId(),today,6);

        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).param("days","14"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days").value(14))
                .andExpect(jsonPath("$.data.fromDate").value(from.toString())).andExpect(jsonPath("$.data.toDate").value(today.toString()))
                .andExpect(jsonPath("$.data.chartDays").value(14)).andExpect(jsonPath("$.data.daily.length()").value(14))
                .andExpect(jsonPath("$.data.daily[0].date").value(from.toString())).andExpect(jsonPath("$.data.daily[0].postViews").value(3))
                .andExpect(jsonPath("$.data.daily[13].date").value(today.toString())).andExpect(jsonPath("$.data.daily[13].postViews").value(5))
                .andExpect(jsonPath("$.data.postViews").value(8)).andExpect(jsonPath("$.data.projectViews").value(10))
                .andExpect(jsonPath("$.data.daily[0].projectViews").value(4)).andExpect(jsonPath("$.data.daily[13].projectViews").value(6));
        mvc.perform(get(INSIGHTS+"/posts/"+post.getId()).header("Authorization","Bearer "+token).param("days","14"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days").value(14))
                .andExpect(jsonPath("$.data.fromDate").value(from.toString())).andExpect(jsonPath("$.data.toDate").value(today.toString()))
                .andExpect(jsonPath("$.data.chartDays").value(14)).andExpect(jsonPath("$.data.daily.length()").value(14))
                .andExpect(jsonPath("$.data.daily[0].date").value(from.toString())).andExpect(jsonPath("$.data.daily[0].views").value(3))
                .andExpect(jsonPath("$.data.daily[13].views").value(5)).andExpect(jsonPath("$.data.item.views").value(8))
                .andExpect(jsonPath("$.data.totalViews").value(28));
        mvc.perform(get(INSIGHTS+"/projects/"+project.getId()).header("Authorization","Bearer "+token).param("days","14"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days").value(14))
                .andExpect(jsonPath("$.data.fromDate").value(from.toString())).andExpect(jsonPath("$.data.toDate").value(today.toString()))
                .andExpect(jsonPath("$.data.chartDays").value(14)).andExpect(jsonPath("$.data.daily.length()").value(14))
                .andExpect(jsonPath("$.data.daily[0].date").value(from.toString())).andExpect(jsonPath("$.data.daily[0].views").value(4))
                .andExpect(jsonPath("$.data.daily[13].views").value(6)).andExpect(jsonPath("$.data.item.views").value(10))
                .andExpect(jsonPath("$.data.totalViews").value(17));
    }
    @Test void unpublishedTargetsCannotBeCounted() throws Exception {
        mvc.perform(post("/api/v1/content-views/posts/9223372036854775807").header("Origin",ORIGIN)).andExpect(status().isNotFound());
        jdbc.update("update post set visibility_status='HIDDEN',is_draft=true where id=?",post.getId());
        mvc.perform(post(path()).header("Origin",ORIGIN)).andExpect(status().isNotFound());
        jdbc.update("update post set visibility_status='PUBLIC',is_draft=false,is_blocked=true,blocked_at=now(),blocked_by_admin_id=1 where id=?",post.getId());
        mvc.perform(post(path()).header("Origin",ORIGIN)).andExpect(status().isNotFound());
        jdbc.update("update project set visibility_status='DELETED',deleted_at=now() where id=?",project.getId());
        mvc.perform(post("/api/v1/content-views/projects/"+project.getId()).header("Origin",ORIGIN)).andExpect(status().isNotFound());
    }
    @Test void optionalSourcePayloadAndPrivateDetailHaveStrictContracts() throws Exception {
        mvc.perform(post(path()).header("Origin",ORIGIN).contentType("application/json").content("{\"source\":\"GOOGLE\"}"))
            .andExpect(status().isNoContent());
        String detail=INSIGHTS+"/posts/"+post.getId();
        mvc.perform(get(detail)).andExpect(status().isUnauthorized());
        mvc.perform(get(detail).header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.item.id").value(post.getId().toString()))
            .andExpect(jsonPath("$.data.totalViews").value(1)).andExpect(jsonPath("$.data.sourceDays").value(7))
            .andExpect(jsonPath("$.data.sourceFromDate").value(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(6).toString()))
            .andExpect(jsonPath("$.data.sources[0].source").value("GOOGLE"))
            .andExpect(jsonPath("$.data.sources[0].views").value(1)).andExpect(jsonPath("$.data.daily.length()").value(30))
            .andExpect(jsonPath("$.data.monthly.length()").value(12));
        mvc.perform(get(INSIGHTS+"/items").header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.items[0].views").value(1));
        mvc.perform(get(INSIGHTS+"/items").header("Authorization","Bearer "+token).param("kind","projects"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].views").value(0));
        mvc.perform(get(INSIGHTS+"/projects/"+project.getId()).header("Authorization","Bearer "+token).param("days","0"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.chartDays").value(90));
    }
    @Test void detailAndListRejectOwnerInjectionDuplicateQueriesAndInvalidSources() throws Exception {
        String detail=INSIGHTS+"/posts/"+post.getId();
        mvc.perform(get(detail).header("Authorization","Bearer "+token).param("days","7","30")).andExpect(status().isBadRequest());
        mvc.perform(get(detail).header("Authorization","Bearer "+token).param("ownerId",owner.getId().toString())).andExpect(status().isBadRequest());
        mvc.perform(get(detail).header("Authorization","Bearer "+token).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get(INSIGHTS+"/posts/9223372036854775807").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        for(String[] pair:new String[][]{{"kind","post"},{"page","10001"},{"page","01"},{"page","-1"},{"query","x".repeat(101)},{"ownerId","1"}})
            mvc.perform(get(INSIGHTS+"/items").header("Authorization","Bearer "+token).param(pair[0],pair[1])).andExpect(status().isBadRequest());
        mvc.perform(get(INSIGHTS+"/items").header("Authorization","Bearer "+token).param("kind","posts","projects")).andExpect(status().isBadRequest());
        for(String payload:new String[]{"{}","{\"source\":\"google\"}","{\"source\":42}","{\"source\":\"GOOGLE\",\"url\":\"https://example.com/private?q=secret\"}","{\"source\":\"GOOGLE\",\"source\":\"NAVER\"}"})
            mvc.perform(post(path()).header("Origin",ORIGIN).contentType("application/json").content(payload)).andExpect(status().isBadRequest());
    }
    @Test void cookieExpiresAtSeoulMidnightAndIsSecureInProduction() {
        var beforeMidnight=ContentInsightsController.dailyVisitorCookie("value",Instant.parse("2026-10-22T14:59:59Z"),true);
        assertThat(beforeMidnight.getMaxAge()).isEqualTo(Duration.ofSeconds(1));
        assertThat(beforeMidnight.isSecure()).isTrue();
        assertThat(ContentInsightsController.dailyVisitorCookie("value",Instant.parse("2026-10-22T15:00:00Z"),false).getMaxAge()).isEqualTo(Duration.ofDays(1));
    }
}
