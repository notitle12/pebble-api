package com.pebble.api.member.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.global.security.JwtProperties;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberIntegrationTest extends AuthenticationTestSupport {

    private static final String PATH = "/api/v1/members/me";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository members;

    @Autowired
    private AccessTokenService accessTokens;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private NaverOAuthGateway naverOAuthGateway;

    @Test
    void returnsOnlyJwtMembersAccountEvenWhenAnotherIdIsRequested() throws Exception {
        Member mine = member(MemberStatus.ACTIVE);
        Member other = members.saveAndFlush(new Member("other", null, MemberStatus.ACTIVE, null, null));

        mockMvc.perform(get(PATH).param("memberId", other.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(mine.getId().toString()))
                .andExpect(jsonPath("$.data.nickname").value("pebble"))
                .andExpect(jsonPath("$.data.profileImageUrl").isEmpty())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.profileCompleted").value(false))
                .andExpect(jsonPath("$.data.handle").isEmpty())
                .andExpect(jsonPath("$.data.length()").value(10));
    }

    @Test
    void refreshCookieAloneDoesNotAuthenticateMemberLookup() throws Exception {
        mockMvc.perform(get(PATH).cookie(new Cookie("refresh_token", "cookie-only")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    void rejectsMissingBearerToken() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsMalformedExpiredAndTamperedBearerTokens() throws Exception {
        Member member = member(MemberStatus.ACTIVE);
        String valid = accessTokens.issueForMember(member.getId(), Instant.now());
        String[] parts = valid.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        for (String token : List.of("invalid", String.join(".", parts),
                accessTokens.issueForMember(member.getId(), Instant.now().minusSeconds(1800)))) {
            mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        }
    }

    @Test
    void rejectsSignedNonUserRolesAndWrongTokenType() throws Exception {
        Member member = member(MemberStatus.ACTIVE);
        for (String role : List.of("MANAGER", "MASTER")) {
            mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + signedToken("member:" + member.getId(), role, "access")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        }
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + signedToken("member:" + member.getId(), "USER", "refresh")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    @Test
    void rejectsSignedSubjectsBeforeTheyReachMemberIdParsing() throws Exception {
        for (String subject : List.of("admin:123", "member:0", "member:invalid")) {
            mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + signedToken(subject, "USER", "access")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        }
    }

    @Test
    void returnsNotFoundForAnAccountMissingFromTheDatabase() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + accessTokens.issueForMember(Long.MAX_VALUE, Instant.now())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    void checksDatabaseSuspensionEvenWhenTokenWasIssuedWhileActive() throws Exception {
        Member member = member(MemberStatus.ACTIVE);
        String bearer = bearer(member);
        jdbc.update("update member set status = 'SUSPENDED' where id = ?", member.getId());
        entityManager.clear();

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    @Test
    void deniesWithdrawalPendingAccount() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member(MemberStatus.WITHDRAWAL_PENDING))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_WITHDRAWAL_PENDING"));
    }

    @Test
    void allowsFrontendGetAndAuthorizationPreflight() throws Exception {
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization"));

        mockMvc.perform(get(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, bearer(member(MemberStatus.ACTIVE))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
    }

    @Test
    void deniesUntrustedOriginsAndUnimplementedCorsMethods() throws Exception {
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mockMvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isForbidden());
        mockMvc.perform(options("/api/v1/auth/token/refresh").header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void continuesToDenyUnimplementedRoutesAndPreserveCsrf() throws Exception {
        String bearer = bearer(member(MemberStatus.ACTIVE));
        mockMvc.perform(get("/api/v1/members/me/projects").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_ROLE"));
        mockMvc.perform(delete(PATH).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_ROLE"));
    }

    private Member member(MemberStatus status) {
        Instant requested = status == MemberStatus.WITHDRAWAL_PENDING ? Instant.now() : null;
        Instant scheduled = requested == null ? null : requested.plus(7, ChronoUnit.DAYS);
        return members.saveAndFlush(new Member("pebble", null, status, requested, scheduled));
    }

    private String bearer(Member member) {
        return "Bearer " + accessTokens.issueForMember(member.getId(), Instant.now());
    }

    private String signedToken(String subject, String role, String type) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(jwtProperties.issuer()).subject(subject)
                .audience(List.of(jwtProperties.audience())).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(900))
                .id("test-token").claim("role", role).claim("token_type", type).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwtProperties.keyId()).build(), claims)).getTokenValue();
    }
}
