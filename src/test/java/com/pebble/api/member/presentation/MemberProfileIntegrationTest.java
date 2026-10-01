package com.pebble.api.member.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberProfileIntegrationTest extends AuthenticationTestSupport {
    private static final String PATH = "/api/v1/members/me/profile";
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired AccessTokenService tokens;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean NaverOAuthGateway naver;
    Member member;

    @BeforeEach
    void setup() { member = newMember(); }

    @Test
    void initialProfileKeepsDefaultNicknameAndFixesHandle() throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON)
                        .content(profile(member))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(member.getNickname()))
                .andExpect(jsonPath("$.data.blogName").value(blog(member)))
                .andExpect(jsonPath("$.data.handle").value(handle(member)))
                .andExpect(jsonPath("$.data.profileCompleted").value(true))
                .andExpect(jsonPath("$.data.nicknameChangeAvailableAt").isNotEmpty())
                .andExpect(jsonPath("$.data.blogNameChangeAvailableAt").isNotEmpty());
        mvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(member)))
                .andExpect(jsonPath("$.data.profileCompleted").value(true));
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(profile(member)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PROFILE_ALREADY_COMPLETED"));
        update("{\"handle\":\"another-id\"}").andExpect(status().isBadRequest());
    }

    @Test
    void nicknameCanBeChosenOnceAtInitialSetupWithoutWaitingForCooldown() throws Exception {
        var request = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(profile(member));
        request.put("nickname", "첫 이름-" + member.getId());
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("첫 이름-" + member.getId()));
        update(mapper.writeValueAsString(Map.of("nickname", "second"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_CHANGE_COOLDOWN"));
    }

    @Test
    void fieldsHaveIndependentCooldownsAndNoOpDoesNotResetDates() throws Exception {
        complete(member);
        String originalNickname = member.getNickname();
        update(mapper.writeValueAsString(Map.of("nickname", originalNickname))).andExpect(status().isOk());
        jdbc.update("update member set nickname_changed_at=current_timestamp - interval '8 days' where id=?", member.getId());
        em.clear();
        update(mapper.writeValueAsString(Map.of("nickname", "changed-" + member.getId()))).andExpect(status().isOk());
        update(mapper.writeValueAsString(Map.of("blogName", "next blog"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BLOG_NAME_CHANGE_COOLDOWN"));
        jdbc.update("update member set blog_name_changed_at=current_timestamp - interval '8 days' where id=?", member.getId());
        em.clear();
        update(mapper.writeValueAsString(Map.of("blogName", "next blog"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("changed-" + member.getId()));
        update(mapper.writeValueAsString(Map.of("nickname", "third"))).andExpect(status().isConflict());
    }

    @Test
    void cooldownFailureDoesNotPartiallyChangeOtherFields() throws Exception {
        complete(member);
        jdbc.update("update member set nickname_changed_at=current_timestamp - interval '8 days' where id=?", member.getId());
        em.clear();
        update(mapper.writeValueAsString(Map.of("nickname", "must-rollback", "blogName", "too-soon")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select nickname from member where id=?", String.class, member.getId())).isEqualTo(member.getNickname());
    }

    @Test
    void rejectsDuplicateNicknameBlogAndHandleWithoutCompletingProfile() throws Exception {
        Member other = newMember();
        complete(other);
        for (Map<String, String> changes : List.of(Map.of("blogName", blog(other)), Map.of("handle", handle(other)), Map.of("nickname", other.getNickname()))) {
            var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(profile(member));
            changes.forEach(json::put);
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                    .andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("select profile_completed_at is null from member where id=?", Boolean.class, member.getId())).isTrue();
    }

    @Test
    void partialUpdatesRequireCompletedProfileAndOmittedValuesStayUnchanged() throws Exception {
        update("{}").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PROFILE_REQUIRED"));
        complete(member);
        update("{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.handle").value(handle(member)));
        for (String invalid : List.of("{\"nickname\":null}", "{\"blogName\":null}", "{\"id\":\"1\"}", "{\"status\":\"ACTIVE\"}")) {
            update(invalid).andExpect(status().isBadRequest());
        }
    }

    @Test
    void normalizesUnicodeAndEnforcesPostgresLengths() throws Exception {
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(profile(member));
        json.put("blogName", "😀".repeat(100));
        json.put("nickname", "😀".repeat(30));
        json.put("handle", "Some-Author");
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.handle").value("some-author"));
        Member another = newMember();
        var tooLong = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(profile(another));
        tooLong.put("blogName", "😀".repeat(101));
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(another)).contentType(MediaType.APPLICATION_JSON).content(tooLong.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details[0].field").value("blogName"));
    }

    @Test
    void rejectsMalformedHandleAndUnknownFields() throws Exception {
        for (String value : List.of("ab", "admin", "search", "한글", "123name", "bad/path", "end-", "a".repeat(31))) {
            var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(profile(member));
            json.put("handle", value);
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                    .andExpect(status().isBadRequest());
        }
        for (String input : List.of("{}", "null", "[]", "{\"blogName\":\"blog\",\"handle\":\"valid\",\"role\":\"MASTER\"}")) {
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void inactiveMemberCannotSetProfileAndRefreshCookieDoesNotAuthenticate() throws Exception {
        jdbc.update("update member set status='SUSPENDED' where id=?", member.getId());
        em.clear();
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(profile(member)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
        mvc.perform(post(PATH).cookie(new Cookie("refresh_token", "cookie-only")).contentType(MediaType.APPLICATION_JSON).content(profile(member)))
                .andExpect(status().isForbidden());
        mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTrustedOriginsAndImplementedMethodsHaveCors() throws Exception {
        for (String method : List.of("POST", "PATCH")) {
            mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "http://localhost:3000").header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
        }
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "https://evil.example").header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isForbidden());
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "http://localhost:3000").header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/token/refresh").header(HttpHeaders.AUTHORIZATION, bearer(member))).andExpect(status().isForbidden());
    }

    @Test
    void databaseAlsoRejectsHandleChange() throws Exception {
        complete(member);
        assertThatThrownBy(() -> jdbc.update("update member set handle='another-handle' where id=?", member.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void complete(Member member) throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(profile(member)))
                .andExpect(status().isOk());
    }
    private ResultActions update(String content) throws Exception {
        return mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(content));
    }
    private String profile(Member member) throws Exception {
        return mapper.writeValueAsString(Map.of("blogName", blog(member), "handle", handle(member)));
    }
    private String blog(Member member) { return "개발 기록-" + member.getId(); }
    private String handle(Member member) { return "author-" + member.getId(); }
    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), Instant.now()); }
    private Member newMember() { return members.saveAndFlush(new Member("member-" + UUID.randomUUID().toString().substring(0, 12), null, MemberStatus.ACTIVE, null, null)); }
}
