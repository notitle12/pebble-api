package com.pebble.api.member.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "pebble.legal.published=true")
@AutoConfigureMockMvc
@Transactional
class MemberAgreementIntegrationTest extends AuthenticationTestSupport {
    private static final String PATH = "/api/v1/members/me/agreements";
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired com.pebble.api.member.application.MemberWithdrawalService withdrawals;
    @Autowired AccessTokenService tokens;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;

    @Test void oldAccountsAreNotBackfilledAndRepeatedConsentKeepsOneServerTimestamp() throws Exception {
        Member member = pending(); String token = bearer(member);
        mvc.perform(get("/api/v1/members/me").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agreementsAccepted").value(false));
        mvc.perform(get(PATH).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(false)).andExpect(jsonPath("$.data.acceptedAt").isEmpty());
        String first = mvc.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(payload()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true)).andReturn().getResponse().getContentAsString();
        String again = mvc.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(payload()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(first).path("data").path("acceptedAt")).isEqualTo(mapper.readTree(again).path("data").path("acceptedAt"));
        assertThat(jdbc.queryForObject("select count(*) from member_agreement where member_id=?", Long.class, member.getId())).isEqualTo(1L);
        mvc.perform(get("/api/v1/members/me").header("Authorization", token)).andExpect(jsonPath("$.data.agreementsAccepted").value(true));
    }

    @Test void signedInPendingAccountCannotWriteAndTheSameTokenWorksAfterConsent() throws Exception {
        Member member = pending(); String token = bearer(member);
        String profile = mapper.writeValueAsString(Map.of("blogName", "블로그-"+member.getId(), "handle", "user-"+member.getId()));
        mvc.perform(post("/api/v1/members/me/profile").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AGREEMENTS_REQUIRED"));
        for (String target : new String[]{"posts", "projects"}) {
            mvc.perform(put("/api/v1/"+target+"/1/like").header("Authorization", token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AGREEMENTS_REQUIRED"));
            mvc.perform(post("/api/v1/"+target+"/1/comments").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"body\":\"동의 전 댓글\",\"visibility\":\"PUBLIC\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AGREEMENTS_REQUIRED"));
        }
        mvc.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/members/me/profile").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(profile)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/posts").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"동의 후 글\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"본문\"}],\"visibilityStatus\":\"PUBLIC\"}"))
                .andExpect(status().isCreated());
    }

    @Test void reconnectedPendingMemberCannotBypassAgreementWithANewToken() throws Exception {
        Member member = pending();
        // 화면을 닫거나 재로그인해서 토큰을 다시 받아도 동의 상태는 서버 DB에서 조회한다.
        String reconnected = "Bearer " + tokens.issueForMember(member.getId(), Instant.now().plusSeconds(1));
        mvc.perform(get("/api/v1/members/me").header("Authorization", reconnected))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agreementsAccepted").value(false));
        for (String target : new String[]{"posts", "projects"}) {
            mvc.perform(post("/api/v1/"+target).header("Authorization", reconnected).contentType(MediaType.APPLICATION_JSON)
                            .content(target.equals("posts")
                                    ? "{\"title\":\"미동의 글\",\"blocks\":[{\"type\":\"TEXT\",\"content\":\"본문\"}],\"visibilityStatus\":\"PUBLIC\"}"
                                    : "{\"name\":\"미동의 프로젝트\",\"lifecycleStatus\":\"IN_PROGRESS\",\"visibilityStatus\":\"PUBLIC\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AGREEMENTS_REQUIRED"));
        }
    }

    @Test void missingUncheckedForgedAndStaleConsentCannotCreateAReceipt() throws Exception {
        Member member = pending(); String token = bearer(member);
        for (String body : new String[]{"{}", payload().replace("true", "false"), payload().replace("true", "\"true\""),
                payload().replace("true", "null"), payload().replace("\"ageConfirmed\":true", "\"ageConfirmed\":true,\"memberId\":\"1\"")}) {
            mvc.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(payload().replace("2026-10-21", "old")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("AGREEMENT_VERSION_CHANGED"));
        assertThat(jdbc.queryForObject("select count(*) from member_agreement where member_id=?", Long.class, member.getId())).isZero();
    }

    @Test void authenticationRolesCurrentAccountStatusAndCorsRemainEnforced() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isForbidden());
        String admin = "Bearer " + tokens.issueForAdmin(1L, "MASTER", "not-a-user", Instant.now());
        mvc.perform(get(PATH).header("Authorization", admin)).andExpect(status().isUnauthorized());
        Member member = pending(); member.changeOperationalStatus(MemberStatus.SUSPENDED); members.flush();
        mvc.perform(post(PATH).header("Authorization", bearer(member)).contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isForbidden());
        mvc.perform(options(PATH).header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(options(PATH).header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }

    @Test void pendingMemberCanWithdrawWithoutAccepting() {
        Member member = pending();
        withdrawals.request(member.getId());
        assertThat(members.existsById(member.getId())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from member_agreement where member_id=?", Long.class, member.getId())).isZero();
    }

    @Test void receiptIsRemovedWithTheAccount() throws Exception {
        Member member = pending();
        mvc.perform(post(PATH).header("Authorization", bearer(member)).contentType(MediaType.APPLICATION_JSON).content(payload())).andExpect(status().isOk());
        long id = member.getId(); members.delete(member); members.flush();
        assertThat(jdbc.queryForObject("select count(*) from member_agreement where member_id=?", Long.class, id)).isZero();
    }

    private Member pending() { return members.saveAndFlush(new Member("consent-"+UUID.randomUUID().toString().substring(0,12), null, MemberStatus.ACTIVE, null, null)); }
    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), Instant.now()); }
    private String payload() { return "{\"termsVersion\":\"2026-10-21\",\"privacyVersion\":\"2026-10-21\",\"termsAccepted\":true,\"privacyAccepted\":true,\"ageConfirmed\":true}"; }
}
