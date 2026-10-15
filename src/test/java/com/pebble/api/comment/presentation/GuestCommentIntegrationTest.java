package com.pebble.api.comment.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.comment.infrastructure.GuestCommentAbuseRecords;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"pebble.legal.published=false","pebble.legal.test-mode=true"})
@AutoConfigureMockMvc
@Transactional
class GuestCommentIntegrationTest extends AuthenticationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired GuestCommentAbuseRecords abuse;
    @MockitoBean(name="naverOAuthClient") OAuthProviderClient naver;
    private Member owner;
    private static final String PASSWORD="comment-password-123";
    private static final String ORIGIN="http://localhost:3000";
    @BeforeEach void setup(){
        var keys=redis.keys("pebble:comment:rate:*"); if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        owner=members.saveAndFlush(agreedMember(new Member("blog-owner",null,MemberStatus.ACTIVE,null,null)));
        profiles.complete(owner.getId(),"blog","blog-"+owner.getId(),null);
    }
    @ParameterizedTest @ValueSource(strings={"posts","projects"})
    void guestLifecycleNeverExposesPasswordHashOrIp(String resource) throws Exception {
        String path=collection(resource,"PUBLIC");
        String result=create(path,"visitor",PASSWORD);
        String id=mapper.readTree(result).at("/data/id").asText();
        assertThat(result).doesNotContain(PASSWORD,"password","ip_address","127.0.0.1","argon2");
        assertThat(mapper.readTree(result).at("/data/author/id").isNull()).isTrue();
        String table=resource.equals("posts")?"post_comment":"project_comment";
        assertThat(jdbc.queryForObject("select guest_password_hash from "+table+" where id=?",String.class,Long.parseLong(id)))
                .startsWith("$argon2id$").doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject("select ip_address from guest_abuse_event where comment_id=?",String.class,Long.parseLong(id))).isEqualTo("127.0.0.1");
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].author.nickname").value("visitor"));
        mvc.perform(post(path+"/"+id+"/guest/update").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("password","wrong-password","body","changed"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("INVALID_COMMENT_PASSWORD"));
        mvc.perform(post(path+"/"+id+"/guest/update").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("password",PASSWORD,"body","changed"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.body").value("changed"));
        mvc.perform(post(path+"/"+id+"/guest/delete").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("password",PASSWORD))))
                .andExpect(status().isNoContent());
        mvc.perform(get(path+"/"+id)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select guest_password_hash from "+table+" where id=?",String.class,Long.parseLong(id))).isNull();
        assertThat(jdbc.queryForObject("select guest_nickname from "+table+" where id=?",String.class,Long.parseLong(id))).isEqualTo("삭제된 방문자");
    }
    @Test void originBodyAndVisibilityCannotBeBypassed() throws Exception {
        String path=collection("posts","PUBLIC");
        String json=payload("visitor",PASSWORD);
        mvc.perform(post(path+"/guest").contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/guest").header("Origin","https://evil.example").contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/guest").header("Origin",ORIGIN,ORIGIN).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/guest?password=leak").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        for(String replacement:new String[]{json.replace("\"privacyAccepted\":true","\"privacyAccepted\":false"),json.replace("\"ageConfirmed\":true","\"ageConfirmed\":false"),json.replace("\"body\":", "\"visibility\":\"SECRET\",\"body\":")})
            mvc.perform(post(path+"/guest").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(replacement)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from guest_abuse_event",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"posts","projects"})
    void hiddenParentsAndWrongContentDoNotLeakAndOwnerCanModerate(String resource) throws Exception {
        String hidden=collection(resource,"HIDDEN"),visible=collection(resource,"PUBLIC"),other=collection(resource,"PUBLIC");
        mvc.perform(post(hidden+"/guest").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(payload("visitor",PASSWORD))).andExpect(status().isNotFound());
        String id=mapper.readTree(create(visible,"visitor",PASSWORD)).at("/data/id").asText();
        mvc.perform(post(other+"/"+id+"/guest/update").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("password",PASSWORD,"body","changed")))).andExpect(status().isNotFound());
        mvc.perform(delete(visible+"/"+id).header("Authorization",bearer(owner))).andExpect(status().isNoContent());
    }
    @Test void memberCommentsCannotBeChangedThroughGuestPasswordRoutes() throws Exception {
        String path=collection("posts","PUBLIC");
        String result=mvc.perform(post(path).header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"member secret\",\"visibility\":\"SECRET\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(result).at("/data/id").asText();
        mvc.perform(post(path+"/"+id+"/guest/delete").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("password",PASSWORD)))).andExpect(status().isNotFound());
        mvc.perform(get(path+"/"+id)).andExpect(status().isNotFound());
    }
    @Test void spoofedForwardingHeadersDoNotBypassRateLimit() throws Exception {
        String path=collection("posts","PUBLIC");
        for(int i=0;i<5;i++) create(path,"visitor",PASSWORD);
        mvc.perform(post(path+"/guest").header("Origin",ORIGIN).header("X-Forwarded-For","203.0.113.99").header("X-Real-IP","203.0.113.100")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("visitor",PASSWORD)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("COMMENT_RATE_LIMITED"));
    }
    @Test void distributedWrongPasswordsAreLimitedPerComment() throws Exception {
        String path=collection("posts","PUBLIC");
        String id=mapper.readTree(create(path,"visitor",PASSWORD)).at("/data/id").asText();
        for(int i=1;i<=15;i++) {
            final String ip="192.0.2."+i;
            mvc.perform(post(path+"/"+id+"/guest/delete").with(request->{request.setRemoteAddr(ip);return request;})
                            .header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("password","wrong-password"))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post(path+"/"+id+"/guest/delete").with(request->{request.setRemoteAddr("192.0.2.200");return request;})
                        .header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("password",PASSWORD))))
                .andExpect(status().isTooManyRequests());
    }

    @Test void abuseRecordsExpireAfterSevenDaysWithoutDeletingComments() throws Exception {
        String path=collection("posts","PUBLIC");
        String id=mapper.readTree(create(path,"visitor",PASSWORD)).at("/data/id").asText();
        jdbc.update("update guest_abuse_event set created_at=now()-interval '8 days'");
        assertThat(abuse.cleanup()).isEqualTo(1);
        mvc.perform(get(path+"/"+id)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from guest_abuse_event",Long.class)).isZero();
    }
    @Test void testUsersCanAcceptEvenWhileNaverReviewIsNotPublished() throws Exception {
        Member pending=members.saveAndFlush(new Member("test-user",null,MemberStatus.ACTIVE,null,null));
        String token=bearer(pending);
        mvc.perform(get("/api/v1/members/me/agreements").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(true)).andExpect(jsonPath("$.data.accepted").value(false));
        mvc.perform(post("/api/v1/members/me/agreements").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termsVersion\":\"2026-10-15\",\"privacyVersion\":\"2026-10-15\",\"termsAccepted\":true,\"privacyAccepted\":true,\"ageConfirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
    }
    private String create(String path,String nickname,String password) throws Exception {
        return mvc.perform(post(path+"/guest").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON).content(payload(nickname,password)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
    private String payload(String nickname,String password) throws Exception {
        return mapper.writeValueAsString(Map.of("nickname",nickname,"password",password,"body","hello guest","privacyAccepted",true,"ageConfirmed",true));
    }
    private String collection(String resource,String visibility) throws Exception {
        String json=resource.equals("posts")?"{\"title\":\"guest parent\",\"visibilityStatus\":\""+visibility+"\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"body\"}]}"
                :"{\"name\":\"guest project\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\""+visibility+"\",\"techStackIds\":[],\"features\":[],\"links\":[]}";
        String result=mvc.perform(post("/api/v1/"+resource).header("Authorization",bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "/api/v1/"+resource+"/"+mapper.readTree(result).at("/data/id").asText()+"/comments";
    }
    private String bearer(Member member){return "Bearer "+tokens.issueForMember(member.getId(),Instant.now());}
}
