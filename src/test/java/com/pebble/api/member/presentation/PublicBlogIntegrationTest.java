package com.pebble.api.member.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pebble.api.auth.application.oauth.OAuthProviderClient;
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
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;

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
    void searchReturnsPublicBlogNamesNicknamesAndHandlesWithStablePages() throws Exception {
        String term = "match-" + UUID.randomUUID().toString().substring(0, 8);
        Member first = searchBlog("a-" + term, "첫번째 " + term, "writer-a-" + term, true);
        Member second = searchBlog("b-" + term, "두번째 " + term, "writer-b-" + term, true);
        Member pending = searchBlog("c-" + term, "탈퇴 " + term, "writer-c-" + term, true);
        searchBlog(null, null, "writer-d-" + term, false);
        pending.requestWithdrawal(Instant.now());
        members.flush();

        mvc.perform(get("/api/v1/blogs").queryParam("q", "  " + term.toUpperCase(java.util.Locale.ROOT) + "  ")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.content[0].length()").value(4))
                .andExpect(jsonPath("$.data.content[0].handle").value(first.getHandle()));
        mvc.perform(get("/api/v1/blogs").queryParam("q", term).queryParam("size", "1").queryParam("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasPrevious").value(true))
                .andExpect(jsonPath("$.data.content[0].handle").value(second.getHandle()));
        for (String search : new String[]{"첫번째", first.getNickname(), "@" + first.getHandle()}) {
            mvc.perform(get("/api/v1/blogs").queryParam("q", search))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].handle").value(first.getHandle()));
        }
        mvc.perform(get("/api/v1/blogs").queryParam("q", term + "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void publicSearchRejectsMalformedQueriesAndWrites() throws Exception {
        mvc.perform(get("/api/v1/blogs")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/blogs").queryParam("q", "one", "two")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/blogs").queryParam("q", "가".repeat(201))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/blogs").queryParam("q", "blog").queryParam("page", "2147483647"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/blogs")).andExpect(status().isForbidden());
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
        return searchBlog("blog-" + unique, "빈 블로그", "writer-" + unique, completed);
    }

    private Member searchBlog(String handle, String blogName, String nickname, boolean completed) {
        Member member = agreedMember(new Member(nickname, "https://example.test/avatar.png", MemberStatus.ACTIVE, null, null));
        if (completed) member.completeProfile(blogName, handle, nickname, Instant.now());
        return members.saveAndFlush(member);
    }

    private String path(Member member) { return "/api/v1/blogs/" + (member.getHandle() == null ? "incomplete-blog" : member.getHandle()); }
}
