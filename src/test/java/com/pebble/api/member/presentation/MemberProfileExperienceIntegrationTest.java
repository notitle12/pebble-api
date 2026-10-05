package com.pebble.api.member.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.global.media.R2ObjectStorage;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MemberProfileExperienceIntegrationTest extends AuthenticationTestSupport {
    private static final String PATH = "/api/v1/members/me/profile";
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean R2ObjectStorage storage;
    final List<Long> ids = new ArrayList<>();
    final List<String> keys = new ArrayList<>();
    Member member;
    String handle;

    @BeforeEach void setup() {
        member = member();
        handle = "test_" + UUID.randomUUID().toString().substring(0,8) + "_";
        when(storage.signedUrl(anyString())).thenAnswer(inv -> "https://images.example/" + inv.getArgument(0));
        doAnswer(inv -> { keys.add(inv.getArgument(0)); return null; }).when(storage).put(anyString(), any());
    }

    @AfterEach void cleanup() {
        for (Long id : ids) members.deleteById(id);
        for (String key : keys) jdbc.update("delete from media_deletion_job where storage_key=?", key);
    }

    @Test void availabilityNormalizesChecksDuplicatesAndExcludesSelf() throws Exception {
        Member other = member();
        mvc.perform(get(PATH + "/availability").header(HttpHeaders.AUTHORIZATION, bearer(member))
                .param("field", "nickname").param("value", other.getNickname()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false));
        mvc.perform(get(PATH + "/availability").header(HttpHeaders.AUTHORIZATION, bearer(member))
                .param("field", "nickname").param("value", " " + member.getNickname() + " "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.value").value(member.getNickname()));
        mvc.perform(get(PATH + "/availability").header(HttpHeaders.AUTHORIZATION, bearer(member))
                .param("field", "handle").param("value", "Valid_Name_"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value").value("valid_name_"));
        mvc.perform(get(PATH + "/availability").param("field", "nickname").param("value", "someone"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/availability").header(HttpHeaders.AUTHORIZATION, bearer(member))
                .param("field", "email").param("value", "someone")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH + "/availability").header(HttpHeaders.AUTHORIZATION, bearer(member))
                .param("field", "nickname", "handle").param("value", "someone")).andExpect(status().isBadRequest());
    }

    @Test void photoAndNamesSaveTogetherAndResetQueuesOldPhoto() throws Exception {
        mvc.perform(multipart(PATH).file(png()).param("profile", initial())
                .header(HttpHeaders.AUTHORIZATION, bearer(member))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handle").value(handle))
                .andExpect(jsonPath("$.data.profileImageUrl").value(org.hamcrest.Matchers.startsWith("https://images.example/member/")));
        String oldKey = members.findById(member.getId()).orElseThrow().getProfileImageStorageKey();
        assertThat(oldKey).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Integer.class, oldKey)).isZero();
        mvc.perform(get("/api/v1/blogs/" + handle)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://images.example/" + oldKey));
        mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"removeProfileImage\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").isEmpty());
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Integer.class, oldKey)).isEqualTo(1);
        mvc.perform(patch(PATH).header(HttpHeaders.AUTHORIZATION, bearer(member)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"handle\":\"changed_id\"}")).andExpect(status().isBadRequest());
    }

    @Test void failedUploadRollsBackCreationAndLeavesRecoveryJob() throws Exception {
        doAnswer(inv -> { keys.add(inv.getArgument(0)); throw new IllegalStateException("test storage failure"); })
                .when(storage).put(anyString(), any());
        mvc.perform(multipart(PATH).file(png()).param("profile", initial())
                .header(HttpHeaders.AUTHORIZATION, bearer(member))).andExpect(status().isInternalServerError());
        assertThat(members.findById(member.getId()).orElseThrow().isProfileCompleted()).isFalse();
        assertThat(keys).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Integer.class, keys.getFirst())).isEqualTo(1);
    }

    @Test void multipartRejectsDuplicateJsonAdditionalFilesAndConflictingReset() throws Exception {
        for (String json : List.of(initial().replace("}", ",\"removeProfileImage\":true}"),
                initial().replace("}", ",\"nickname\":\"a\",\"nickname\":\"b\"}"), initial() + " {}")) {
            mvc.perform(multipart(PATH).file(png()).param("profile", json)
                    .header(HttpHeaders.AUTHORIZATION, bearer(member))).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart(PATH).file(png()).file(png()).param("profile", initial())
                .header(HttpHeaders.AUTHORIZATION, bearer(member))).andExpect(status().isBadRequest());
        verify(storage, never()).put(anyString(), any());
        assertThat(members.findById(member.getId()).orElseThrow().isProfileCompleted()).isFalse();
    }

    @Test void deletingMemberQueuesUploadedProfilePhoto() throws Exception {
        mvc.perform(multipart(PATH).file(png()).param("profile", initial())
                .header(HttpHeaders.AUTHORIZATION, bearer(member))).andExpect(status().isOk());
        String key = members.findById(member.getId()).orElseThrow().getProfileImageStorageKey();
        members.deleteById(member.getId()); ids.remove(member.getId());
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Integer.class, key)).isEqualTo(1);
    }

    private Member member() {
        Member saved = members.saveAndFlush(new Member("name-" + UUID.randomUUID().toString().substring(0,12),
                "https://example.com/sns.png", MemberStatus.ACTIVE, null, null));
        ids.add(saved.getId()); return saved;
    }
    private String initial() { return "{\"blogName\":\"blog-" + member.getId() + "\",\"handle\":\"" + handle + "\"}"; }
    private String bearer(Member member) { return "Bearer " + tokens.issueForMember(member.getId(), java.time.Instant.now()); }
    private MockMultipartFile png() throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "profile.png", "image/png", out.toByteArray());
    }
}
