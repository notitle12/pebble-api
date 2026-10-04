package com.pebble.api.member.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicBlogIntegrationTest extends AuthenticationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean NaverOAuthGateway naver;

    @Test
    void emptyBlogHasPublicProfileWithoutPrivateMemberInformation() throws Exception {
        Member member = blog(true);
        mvc.perform(get(path(member))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data.id").value(member.getId().toString()))
                .andExpect(jsonPath("$.data.handle").value(member.getHandle()))
                .andExpect(jsonPath("$.data.blogName").value("빈 블로그"))
                .andExpect(jsonPath("$.data.nickname").value(member.getNickname()))
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://example.test/avatar.png"))
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.email").doesNotExist());
        mvc.perform(get(path(member) + "/posts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void missingIncompleteAndWithdrawalPendingBlogsAreHidden() throws Exception {
        mvc.perform(get("/api/v1/blogs/missing-blog")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/blogs/Bad-handle")).andExpect(status().isNotFound());
        Member incomplete = blog(false);
        mvc.perform(get(path(incomplete))).andExpect(status().isNotFound());
        Member member = blog(true);
        member.requestWithdrawal(Instant.now());
        members.flush();
        em.clear();
        mvc.perform(get(path(member))).andExpect(status().isNotFound());
    }

    @Test
    void suspendedBlogStillHasExistingPublicProfile() throws Exception {
        Member member = blog(true);
        jdbc.update("update member set status='SUSPENDED' where id=?", member.getId());
        em.clear();
        mvc.perform(get(path(member))).andExpect(status().isOk());
    }

    @Test
    void rejectsUnexpectedQueryAndBodyAndUnimplementedPublicWrites() throws Exception {
        Member member = blog(true);
        mvc.perform(get(path(member)).queryParam("memberId", "1")).andExpect(status().isBadRequest());
        mvc.perform(get(path(member)).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path(member))).andExpect(status().isForbidden());
    }

    @Test
    void corsOnlyAllowsTrustedOriginAndGet() throws Exception {
        String path = path(blog(true));
        mvc.perform(options(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
        mvc.perform(options(path).header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(options(path).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
    }

    private Member blog(boolean completed) {
        String unique = UUID.randomUUID().toString().substring(0, 12);
        Member member = new Member("writer-" + unique, "https://example.test/avatar.png", MemberStatus.ACTIVE, null, null);
        if (completed) member.completeProfile("빈 블로그", "blog-" + unique, member.getNickname(), Instant.now());
        return members.saveAndFlush(member);
    }

    private String path(Member member) { return "/api/v1/blogs/" + (member.getHandle() == null ? "incomplete-blog" : member.getHandle()); }
}
