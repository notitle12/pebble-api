package com.pebble.api.member.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BlogFollowIntegrationTest extends AuthenticationTestSupport {
    private static final String MINE = "/api/v1/members/me/blog-follows";
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired AccessTokenService tokens;

    @Test
    void countsIncludeOneWayFollowsButOnlyMutualFollowersAppearInTheOwnerList() throws Exception {
        Member owner = blog(), mutual = blog(), incoming = blog(), outgoing = blog();
        follow(mutual, owner);
        follow(mutual, owner);
        follow(incoming, owner);
        follow(owner, mutual);
        follow(owner, outgoing);

        mvc.perform(get("/api/v1/blogs/" + owner.getHandle())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followerCount").value(2))
                .andExpect(jsonPath("$.data.followingCount").value(2))
                .andExpect(jsonPath("$.data.followers").doesNotExist());
        mvc.perform(get(MINE).param("direction", "followers").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].handle").value(mutual.getHandle()));
        mvc.perform(get(MINE).param("direction", "following").param("size", "1").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].handle").value(outgoing.getHandle()))
                .andExpect(jsonPath("$.data.last").value(false));
        mvc.perform(get(MINE).param("direction", "following").param("size", "1").param("page", "1").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].handle").value(mutual.getHandle()));
        mvc.perform(get(MINE).param("direction", "followers").header("Authorization", bearer(incoming)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(0));

        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(delete(path(owner)).header("Authorization", bearer(mutual))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.following").value(false)).andExpect(jsonPath("$.data.followerCount").value(1));
        }
        mvc.perform(get(MINE).param("direction", "followers").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    void rejectsUnauthenticatedCrossAccountSelfAndMalformedRequests() throws Exception {
        Member owner = blog(), visitor = blog();
        mvc.perform(get(MINE).param("direction", "followers")).andExpect(status().isUnauthorized());
        mvc.perform(put(path(owner))).andExpect(status().isForbidden());
        mvc.perform(put(path(owner)).header("Authorization", bearer(owner))).andExpect(status().isBadRequest());
        mvc.perform(get(MINE).param("direction", "followers").param("memberId", owner.getId().toString())
                .header("Authorization", bearer(visitor))).andExpect(status().isBadRequest());
        mvc.perform(get(MINE).param("direction", "following").param("size", "51")
                .header("Authorization", bearer(visitor))).andExpect(status().isBadRequest());
        mvc.perform(get(path(owner)).header("Authorization", bearer(visitor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(false));
    }

    private void follow(Member actor, Member target) throws Exception {
        mvc.perform(put(path(target)).header("Authorization", bearer(actor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.following").value(true));
    }

    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), Instant.now()); }
    private String path(Member member) { return "/api/v1/members/me/blogs/" + member.getHandle() + "/follow"; }

    private Member blog() {
        String key = "follow-" + UUID.randomUUID().toString().substring(0, 12);
        var member = agreedMember(new Member(key, null, MemberStatus.ACTIVE, null, null));
        member.completeProfile(key, key, key, Instant.now());
        return members.saveAndFlush(member);
    }
}
