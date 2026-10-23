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
        for(String days:new String[]{"1","-1","007","91",""}) mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).param("days",days)).andExpect(status().isBadRequest());
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).param("days","7","30")).andExpect(status().isBadRequest());
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).param("memberId",owner.getId().toString())).andExpect(status().isBadRequest());
        mvc.perform(get(INSIGHTS).header("Authorization","Bearer "+token).content("{}" )).andExpect(status().isBadRequest());
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
