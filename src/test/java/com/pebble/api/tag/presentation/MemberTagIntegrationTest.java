package com.pebble.api.tag.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.infrastructure.persistence.TagRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberTagIntegrationTest extends AuthenticationTestSupport {
    private static final String PATH = "/api/v1/tags";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired TagRepository tags;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository admins;
    @Autowired com.pebble.api.auth.application.AdminSessionService adminSessions;
    @Autowired com.pebble.api.auth.application.AdminRefreshTokenService adminRefreshTokens;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;
    Member owner;
    Member other;
    Long committedTagId;

    @BeforeEach
    void setup() {
        owner = member(true);
        other = member(true);
    }

    @AfterEach
    void cleanCommittedConcurrencyFixtures() {
        if (!TestTransaction.isActive()) {
            if (committedTagId != null) tags.deleteById(committedTagId);
            members.deleteById(owner.getId());
            members.deleteById(other.getId());
        }
    }

    @Test
    void normalizesNamesCreatesServerMetadataAndReusesWithoutChangingTimestamps() throws Exception {
        String name = "자유-" + UUID.randomUUID();
        String id = id(create(owner, "  #" + name.toUpperCase(java.util.Locale.ROOT) + "  ")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value(name))
                .andExpect(jsonPath("$.data.slug").value(org.hamcrest.Matchers.matchesPattern("user-[a-f0-9]{64}")))
                .andExpect(jsonPath("$.data.displayOrder").value(0))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.length()").value(5)));
        Tag tag = tags.findById(Long.parseLong(id)).orElseThrow();
        assertThat(tag.getCreatedAt()).isEqualTo(tag.getUpdatedAt());
        Instant createdAt = tag.getCreatedAt();
        Instant updatedAt = tag.getUpdatedAt();
        create(other, name).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        assertThat(tags.findById(tag.getId()).orElseThrow().getCreatedAt()).isEqualTo(createdAt);
        assertThat(tags.findById(tag.getId()).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
        create(owner, "＃ＦｕｌｌＷｉｄｔｈ-" + UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(org.hamcrest.Matchers.startsWith("fullwidth-")));
        create(owner, "𐐀".repeat(50)).andExpect(status().isOk());
        create(owner, "C++").andExpect(status().isOk());
        create(owner, "C#").andExpect(status().isBadRequest());
        create(owner, "a\u0301").andExpect(status().isOk());
    }

    @Test
    void reusesActiveAdminNameFirstAndLowestIdWithoutReactivatingInactiveTags() throws Exception {
        String name = "reuse-" + UUID.randomUUID();
        Tag inactive = tags.saveAndFlush(new Tag(name.toUpperCase(java.util.Locale.ROOT), "test-" + UUID.randomUUID(), 3, TagStatus.INACTIVE));
        Tag first = tags.saveAndFlush(new Tag(name, "test-" + UUID.randomUUID(), 7, TagStatus.ACTIVE));
        tags.saveAndFlush(new Tag(name, "test-" + UUID.randomUUID(), 1, TagStatus.ACTIVE));
        create(owner, "#" + name).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(first.getId().toString()))
                .andExpect(jsonPath("$.data.displayOrder").value(7)).andExpect(jsonPath("$.data.slug").value(first.getSlug()));
        assertThat(tags.findById(inactive.getId()).orElseThrow().getStatus()).isEqualTo(TagStatus.INACTIVE);
        String blocked = "inactive-" + UUID.randomUUID();
        Tag disabled = tags.saveAndFlush(new Tag(blocked, "test-" + UUID.randomUUID(), 0, TagStatus.INACTIVE));
        create(other, blocked.toUpperCase(java.util.Locale.ROOT)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INACTIVE_TAG"));
        assertThat(tags.findForNameReuse(blocked)).hasSize(1);
        assertThat(tags.findById(disabled.getId()).orElseThrow().getStatus()).isEqualTo(TagStatus.INACTIVE);
    }

    @Test
    void rejectsAHashSlugClaimedByAnUnrelatedNameAndKeepsInactivePrecedence() throws Exception {
        String name = "claimed-" + UUID.randomUUID();
        String slug = "user-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Tag claimed = tags.saveAndFlush(new Tag("unrelated", slug, 4, TagStatus.ACTIVE));
        create(owner, name).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TAG_SLUG_CONFLICT"));
        claimed.update("unrelated", slug, 4, TagStatus.INACTIVE);
        tags.flush();
        create(owner, name).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INACTIVE_TAG"));
        assertThat(tags.findById(claimed.getId()).orElseThrow().getName()).isEqualTo("unrelated");
    }

    @Test
    void rejectsInvalidNamesAndAnythingOutsideTheExactJsonContract() throws Exception {
        for (String name : List.of("", " ", "#", "##java", "# java", "has space", "<script>", "a/b", "a\\b", "a\tinside",
                "a\u0000", "a\uD800", "a\uDC00", "😀", ".+-_", "\u0301", "a".repeat(51), "𐐀".repeat(51))) {
            create(owner, name).andExpect(status().isBadRequest());
        }
        for (String raw : List.of("{}", "null", "[]", "{\"name\":null}", "{\"name\":1}", "{\"name\":\"java\",\"slug\":\"java\"}",
                "{\"name\":\"java\",\"name\":\"spring\"}", "{\"name\":\"java\"} {}")) {
            request(owner, raw).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(owner)).queryParam("name", "java")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"java\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void requiresBearerUserActiveStateAndCompletedProfile() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"java\"}")).andExpect(status().isForbidden());
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"java\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).cookie(new jakarta.servlet.http.Cookie("refresh_token", "cookie-only"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"java\"}")).andExpect(status().isForbidden());
        var admin = admins.saveAndFlush(new com.pebble.api.admin.domain.AdminAccount("tag-manager-" + UUID.randomUUID(),
                passwords.encode("Tag-Integration-2026"), com.pebble.api.admin.domain.AdminRole.MANAGER));
        String token = adminSessions.login(admin.getLoginId(), "Tag-Integration-2026").tokens().accessToken();
        try {
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"java\"}")).andExpect(status().isForbidden());
        } finally { adminRefreshTokens.revokeAll(admin.getId()); }
        create(member(false), "java").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PROFILE_REQUIRED"));
        jdbc.update("update member set status='SUSPENDED' where id=?", owner.getId());
        em.clear();
        create(owner, "java").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=now(), withdrawal_scheduled_at=now()+interval '7 days' where id=?", other.getId());
        em.clear();
        create(other, "java").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCOUNT_WITHDRAWAL_PENDING"));
    }

    @Test
    void createdTagConnectsThroughExistingPostTagIds() throws Exception {
        String tagId = id(create(owner, "post-tag-" + UUID.randomUUID()).andExpect(status().isOk()));
        var post = mapper.createObjectNode().put("title", "자유 태그 연결").put("visibilityStatus", "PUBLIC");
        post.putArray("tagIds").add(tagId);
        post.putArray("blocks").addObject().put("type", "TEXT").put("content", "본문");
        mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(post.toString())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tags[0].id").value(tagId));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentMembersReceiveOneCommittedTag() throws Exception {
        String name = "concurrent-" + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return id(create(owner, name).andExpect(status().isOk())); });
            var second = executor.submit(() -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return id(create(other, name.toUpperCase(java.util.Locale.ROOT)).andExpect(status().isOk())); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String firstId = first.get(20, TimeUnit.SECONDS);
            committedTagId = Long.parseLong(firstId);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(firstId);
            assertThat(jdbc.queryForObject("select count(*) from tag where name=?", Long.class, name)).isEqualTo(1L);
            Tag saved = tags.findById(committedTagId).orElseThrow();
            assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt());
        } finally { start.countDown(); }
    }

    private Member member(boolean completed) {
        Member member = members.saveAndFlush(new Member("tag-" + UUID.randomUUID().toString().substring(0, 8), null, MemberStatus.ACTIVE, null, null));
        return completed ? profiles.complete(member.getId(), "blog-" + member.getId(), "author-" + member.getId(), null) : member;
    }

    private ResultActions create(Member member, String name) throws Exception {
        return request(member, mapper.writeValueAsString(java.util.Map.of("name", name)));
    }
    private ResultActions request(Member member, String raw) throws Exception {
        return mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON).content(raw));
    }
    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), Instant.now()); }
    private String id(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()).at("/data/id").asText(); }
}
