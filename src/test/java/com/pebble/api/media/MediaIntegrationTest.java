package com.pebble.api.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.global.media.R2ObjectStorage;
import com.pebble.api.global.media.MediaDeletionQueue;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.application.MemberWithdrawalService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.application.PostMediaService;
import com.pebble.api.project.application.ProjectMediaService;
import com.pebble.api.project.application.ProjectService;
import com.pebble.api.project.domain.MediaRole;
import com.pebble.api.support.AuthenticationTestSupport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import jakarta.servlet.http.Cookie;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R2는 대역 처리하고 실제 이미지 변환 및 DB·서비스 흐름을 확인한다. */
@SpringBootTest(properties = "pebble.media.r2.enabled=false")
@AutoConfigureMockMvc
class MediaIntegrationTest extends AuthenticationTestSupport {
    @Autowired PostMediaService postMedia;
    @Autowired ProjectMediaService projectMedia;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired ProjectService projects;
    @Autowired MemberWithdrawalService withdrawals;
    @Autowired MediaDeletionQueue deletionQueue;
    @Autowired AccessTokenService tokens;
    @Autowired com.pebble.api.auth.application.AdminRefreshTokenService adminSessions;
    @Autowired com.pebble.api.post.application.PostService posts;
    @Autowired MockMvc mvc;
    @MockitoBean R2ObjectStorage storage;

    private final List<Long> fixtureMembers = new ArrayList<>();
    private final List<Long> postIds = new ArrayList<>();
    private final List<Long> projectIds = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private final List<byte[]> uploadedBytes = new ArrayList<>();
    private Member owner;
    private Member other;
    private long postId;
    private long projectId;
    private boolean failPut;
    private boolean failDelete;

    @BeforeEach
    void setUp() {
        reset(storage);
        failPut = false;
        failDelete = false;
        when(storage.signedUrl(anyString())).thenAnswer(call -> "https://media.test/" + call.getArgument(0));
        doAnswer(call -> {
            objectKeys.add(call.getArgument(0));
            uploadedBytes.add(call.getArgument(1));
            if (failPut) throw new IllegalStateException("simulated storage error");
            return null;
        }).when(storage).put(anyString(), any());
        doAnswer(call -> {
            if (failDelete) throw new IllegalStateException("simulated delete error");
            return null;
        }).when(storage).delete(anyString());
        owner = member("owner");
        other = member("other");
        postId = TsidGenerator.generate();
        projectId = TsidGenerator.generate();
        postIds.add(postId);
        projectIds.add(projectId);
        jdbc.update("insert into post(id,author_member_id,post_number,title,visibility_status,created_at,updated_at) values (?,?,1,'media post','PUBLIC',now(),now())",
                postId, owner.getId());
        jdbc.update("insert into project(id,owner_member_id,name,lifecycle_status,visibility_status,created_at,updated_at) values (?,?,'media project','IN_PROGRESS','PUBLIC',now(),now())",
                projectId, owner.getId());
    }

    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> {
            for (long id : postIds) jdbc.update("delete from post where id=?", id);
            for (long id : projectIds) jdbc.update("delete from project where id=?", id);
            for (long id : fixtureMembers) jdbc.update("delete from member where id=?", id);
            for (String key : objectKeys) jdbc.update("delete from media_deletion_job where storage_key=?", key);
        });
    }

    @Test
    void postUploadConvertsImageStoresWebpAndReturnsSignedUrlThenDeleteStagesReferencedKey() throws Exception {
        String url = postMedia.upload(owner.getId(), postId, png());
        assertThat(url).startsWith("https://media.test/post/").endsWith("/thumbnail.webp");
        assertThat(objectKeys).hasSize(1);
        assertThat(new String(uploadedBytes.getFirst(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(uploadedBytes.getFirst(), 8, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("WEBP");
        assertThat(jdbc.queryForObject("select thumbnail_storage_key from post where id=?", String.class, postId))
                .isEqualTo(objectKeys.getFirst());
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Long.class, objectKeys.getFirst())).isZero();

        postMedia.delete(owner.getId(), postId);
        assertThat(jdbc.queryForObject("select thumbnail_storage_key from post where id=?", String.class, postId)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Long.class, objectKeys.getFirst())).isEqualTo(1);
    }

    @Test
    void projectThumbnailIsUniqueAndMetadataUpdateDoesNotReplaceStoredObjects() throws Exception {
        var created = projectMedia.upload(owner.getId(), projectId, png(), MediaRole.THUMBNAIL, "cover", 0);
        assertThat(created.url()).contains("/project/");
        assertThat(created.thumbnailUrl()).contains("/project/");
        assertThat(objectKeys).hasSize(2);
        long mediaId = Long.parseLong(created.id());

        assertThatThrownBy(() -> projectMedia.upload(owner.getId(), projectId, png(), MediaRole.THUMBNAIL, null, 0))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.error().code()).isEqualTo("THUMBNAIL_ALREADY_EXISTS"));
        var updated = projectMedia.update(owner.getId(), projectId, mediaId, true, "new description", 4);
        assertThat(updated.altText()).isEqualTo("new description");
        assertThat(updated.displayOrder()).isEqualTo(4);
        assertThat(objectKeys).hasSize(2);

        projectMedia.delete(owner.getId(), projectId, mediaId);
        assertThat(jdbc.queryForObject("select count(*) from project_media where id=?", Long.class, mediaId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key in (?,?)", Long.class,
                objectKeys.get(0), objectKeys.get(1))).isEqualTo(2);
    }

    @Test
    void otherOwnerCannotUploadAndFailedPutLeavesStagedDeletionWork() throws Exception {
        assertThatThrownBy(() -> postMedia.upload(other.getId(), postId, png()))
                .isInstanceOf(ApplicationException.class);
        assertThat(objectKeys).isEmpty();

        failPut = true;
        assertThatThrownBy(() -> postMedia.upload(owner.getId(), postId, png()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(objectKeys).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Long.class, objectKeys.getFirst())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select thumbnail_storage_key from post where id=?", String.class, postId)).isNull();
        verify(storage).put(anyString(), any());
    }

    @Test
    void hiddenBlockedAndWithdrawalPendingContentNeverGetsFreshSignedUrls() throws Exception {
        jdbc.update("update post set visibility_status='HIDDEN' where id=?", postId);
        assertThat(postMedia.upload(owner.getId(), postId, png())).isNull();
        jdbc.update("update project set is_blocked=true,blocked_at=now(),blocked_by_admin_id=1 where id=?", projectId);
        var hiddenProjectMedia = projectMedia.upload(owner.getId(), projectId, png(), MediaRole.SCREENSHOT, null, 0);
        assertThat(hiddenProjectMedia.url()).isNull();
        assertThat(hiddenProjectMedia.thumbnailUrl()).isNull();

        jdbc.update("update member set status='WITHDRAWAL_PENDING',withdrawal_requested_at=now(),withdrawal_scheduled_at=now()+interval '7 days' where id=?", owner.getId());
        org.mockito.Mockito.clearInvocations(storage);
        assertThatThrownBy(() -> postMedia.upload(owner.getId(), postId, png())).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> projectMedia.upload(owner.getId(), projectId, png(), MediaRole.SCREENSHOT, null, 1))
                .isInstanceOf(ApplicationException.class);
        verify(storage, org.mockito.Mockito.never()).signedUrl(anyString());
    }

    @Test
    void incompleteAndSuspendedProfilesCannotWriteMediaBeforeStorageAccess() throws Exception {
        Member incomplete = members.saveAndFlush(new Member("media-incomplete-" + UUID.randomUUID().toString().substring(0, 6),
                null, MemberStatus.ACTIVE, null, null));
        fixtureMembers.add(incomplete.getId());
        org.mockito.Mockito.clearInvocations(storage);
        assertThatThrownBy(() -> postMedia.upload(incomplete.getId(), postId, png())).isInstanceOf(ApplicationException.class);
        jdbc.update("update member set status='SUSPENDED' where id=?", owner.getId());
        assertThatThrownBy(() -> projectMedia.upload(owner.getId(), projectId, png(), MediaRole.SCREENSHOT, null, 0))
                .isInstanceOf(ApplicationException.class);
        verify(storage, org.mockito.Mockito.never()).put(anyString(), any());
        verify(storage, org.mockito.Mockito.never()).signedUrl(anyString());
    }

    @Test
    void projectDeleteAndExpiredWithdrawalPreserveCascadeDeletionJobsWithoutTouchingOtherOwnersFiles() throws Exception {
        var projectOwnedMedia = projectMedia.upload(owner.getId(), projectId, png(), MediaRole.SCREENSHOT, null, 0);
        Member pending = member("pending");
        long pendingProject = insertProject(pending.getId());
        projectIds.add(pendingProject);
        var pendingMedia = projectMedia.upload(pending.getId(), pendingProject, png(), MediaRole.SCREENSHOT, null, 0);
        var otherMedia = projectMedia.upload(other.getId(), insertAndTrackProject(other.getId()), png(), MediaRole.SCREENSHOT, null, 0);

        projects.delete(projectId, owner.getId());
        assertThat(jdbc.queryForObject("select count(*) from project_media where project_id=?", Long.class, projectId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key in (?,?)", Long.class,
                objectKeys.get(0), objectKeys.get(1))).isEqualTo(2);

        jdbc.update("update member set status='WITHDRAWAL_PENDING',withdrawal_requested_at=now()-interval '8 days',withdrawal_scheduled_at=now()-interval '1 day' where id=?", pending.getId());
        assertThat(withdrawals.deleteExpired(pending.getId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from member where id=?", Long.class, pending.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key in (?,?)", Long.class,
                objectKeys.get(2), objectKeys.get(3))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from project_media where id=?", Long.class, Long.parseLong(otherMedia.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key in (?,?)", Long.class,
                objectKeys.get(4), objectKeys.get(5))).isZero();
        assertThat(projectOwnedMedia.id()).isNotEqualTo(pendingMedia.id());
    }

    @Test
    void deletionQueueKeepsReferencedKeysRetriesFailuresAndDeletesHealthyObjects() {
        String referenced = "post/media-test/retained.webp";
        objectKeys.add(referenced);
        jdbc.update("update post set thumbnail_storage_key=? where id=?", referenced, postId);
        jdbc.update("insert into media_deletion_job(storage_key,next_attempt_at) values (?,now()-interval '1 second')", referenced);
        deletionQueue.deleteDue(referenced);
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Long.class, referenced)).isZero();
        verify(storage, org.mockito.Mockito.never()).delete(referenced);

        String failed = "media-test/failed-" + UUID.randomUUID();
        objectKeys.add(failed);
        jdbc.update("insert into media_deletion_job(storage_key,next_attempt_at) values (?,now()-interval '1 second')", failed);
        failDelete = true;
        deletionQueue.deleteDue(failed);
        var nextAttempt = jdbc.queryForObject("select next_attempt_at from media_deletion_job where storage_key=?", java.sql.Timestamp.class, failed).toInstant();
        assertThat(nextAttempt).isBetween(Instant.now().plusSeconds(285), Instant.now().plusSeconds(315));

        failDelete = false;
        jdbc.update("update media_deletion_job set next_attempt_at=now()-interval '1 second' where storage_key=?", failed);
        deletionQueue.deleteDue(failed);
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Long.class, failed)).isZero();
    }

    @Test
    void rollbackAfterReplacingThumbnailRetainsOldReferenceAndKeepsNewStagedJob() throws Exception {
        String oldKey = postMedia.upload(owner.getId(), postId, png());
        String oldStorageKey = objectKeys.getFirst();
        transactions.executeWithoutResult(status -> {
            postMedia.upload(owner.getId(), postId, png());
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("select thumbnail_storage_key from post where id=?", String.class, postId)).isEqualTo(oldStorageKey);
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Long.class, oldStorageKey)).isZero();
        assertThat(objectKeys).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Long.class, objectKeys.get(1))).isEqualTo(1);
        assertThat(oldKey).contains(oldStorageKey);
    }

    @Test
    void mediaHttpEndpointsEnforceBearerRoleAndRejectMalformedMultipartAndPatchInput() throws Exception {
        String token = "Bearer " + tokens.issueForMember(owner.getId(), Instant.now());
        String admin = "Bearer " + tokens.issueForAdmin(987654, "MANAGER", UUID.randomUUID().toString(), Instant.now());
        String postPath = "/api/v1/posts/" + postId + "/thumbnail";
        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", png());

        multipartPut(postPath, file).andExpect(status().isForbidden());
        multipartPut(postPath, file, admin).andExpect(status().isUnauthorized());
        mvc.perform(multipartPutBuilder(postPath, file).cookie(new Cookie("refresh_token", "not-an-access-token")))
                .andExpect(status().isForbidden());
        multipartPut(postPath, file, token).andExpect(status().isOk()).andExpect(jsonPath("$.data.thumbnailUrl").isNotEmpty());
        mvc.perform(multipartPutBuilder(postPath, file).header(HttpHeaders.AUTHORIZATION, token).param("unexpected", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipartPutBuilder(postPath, file).header(HttpHeaders.AUTHORIZATION, token).queryParam("unexpected", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipartPutBuilder(postPath).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isBadRequest());
        mvc.perform(multipartPutBuilder(postPath, file, file).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isBadRequest());

        String projectPath = "/api/v1/projects/" + projectId + "/media";
        mvc.perform(multipart(projectPath).file(file).param("mediaRole", "SCREENSHOT").param("displayOrder", "0"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(projectPath).file(file).header(HttpHeaders.AUTHORIZATION, admin)
                        .param("mediaRole", "SCREENSHOT").param("displayOrder", "0"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(projectPath).file(file).header(HttpHeaders.AUTHORIZATION, token).param("mediaRole", "SCREENSHOT")
                        .param("displayOrder", "0").queryParam("unexpected", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(projectPath).file(file).header(HttpHeaders.AUTHORIZATION, token)
                        .param("mediaRole", "SCREENSHOT").param("displayOrder", "0").param("unexpected", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(projectPath).header(HttpHeaders.AUTHORIZATION, token)
                        .param("mediaRole", "SCREENSHOT").param("displayOrder", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(projectPath).file(file).header(HttpHeaders.AUTHORIZATION, token)
                        .param("mediaRole", "SCREENSHOT").param("displayOrder", "0", "1"))
                .andExpect(status().isBadRequest());
        var upload = mvc.perform(multipart(projectPath).file(file).header(HttpHeaders.AUTHORIZATION, token)
                        .param("mediaRole", "SCREENSHOT").param("displayOrder", "0"))
                .andExpect(status().isCreated()).andReturn();
        String mediaId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(upload.getResponse().getContentAsByteArray())
                .at("/data/id").asText();
        mvc.perform(patch(projectPath + "/" + mediaId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"altText\":null,\"displayOrder\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.altText").isEmpty()).andExpect(jsonPath("$.data.displayOrder").value(3));
        mvc.perform(multipart(projectPath).file(file).file(file).header(HttpHeaders.AUTHORIZATION, token)
                        .param("mediaRole", "SCREENSHOT").param("displayOrder", "0"))
                .andExpect(status().isBadRequest());

        mvc.perform(options(postPath).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, org.hamcrest.Matchers.containsString("PUT")));
    }

    @Test
    void cleanupSkipsAnUploadReservationLockedByAnotherTransaction() {
        String key = "post/" + UUID.randomUUID() + "/thumbnail.webp";
        objectKeys.add(key);
        deletionQueue.stage(List.of(key));
        jdbc.update("update media_deletion_job set next_attempt_at=now()-interval '1 second' where storage_key=?", key);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            transactions.executeWithoutResult(status -> {
                deletionQueue.lockStaged(List.of(key));
                try {
                    executor.submit(() -> deletionQueue.deleteDue(key)).get(3, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
                verify(storage, org.mockito.Mockito.never()).delete(key);
                status.setRollbackOnly();
            });
            deletionQueue.deleteDue(key);
            verify(storage).delete(key);
            assertThat(jdbc.queryForObject("select count(*) from media_deletion_job where storage_key=?", Long.class, key)).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void activeManagerSessionCannotUseUserMediaEndpoints() throws Exception {
        long adminId = TsidGenerator.generate();
        String sid = UUID.randomUUID().toString();
        jdbc.update("insert into admin_account(id,login_id,password_hash,role,status,created_at,updated_at) values (?,?,'unused-test-hash','MANAGER','ACTIVE',now(),now())",
                adminId, "media-admin-" + adminId);
        try {
            adminSessions.issue(adminId, "MANAGER", sid, Instant.now());
            String bearer = "Bearer " + tokens.issueForAdmin(adminId, "MANAGER", sid, Instant.now());
            var file = new MockMultipartFile("file", "image.png", "image/png", png());
            multipartPut("/api/v1/posts/" + postId + "/thumbnail", file, bearer).andExpect(status().isForbidden());
            mvc.perform(multipart("/api/v1/projects/" + projectId + "/media").file(file)
                    .header(HttpHeaders.AUTHORIZATION, bearer).param("mediaRole", "SCREENSHOT").param("displayOrder", "0"))
                    .andExpect(status().isForbidden());
            assertThat(objectKeys).isEmpty();
        } finally {
            adminSessions.revokeAll(adminId);
            jdbc.update("delete from admin_account where id=?", adminId);
        }
    }

    @Test
    void resourceViewsIssueUrlsOnlyWhilePublicAndPreserveLikeDecoration() throws Exception {
        postMedia.upload(owner.getId(), postId, png());
        projectMedia.upload(owner.getId(), projectId, png(), MediaRole.SCREENSHOT, null, 0);
        assertThat(posts.detail(postId, null).thumbnailUrl()).isNotNull();
        assertThat(projects.detail(projectId, null).media()).hasSize(1);
        assertThat(projects.detail(projectId, null).media().getFirst().url()).isNotNull();
        jdbc.update("update post set visibility_status='HIDDEN' where id=?", postId);
        jdbc.update("update project set visibility_status='HIDDEN' where id=?", projectId);
        org.mockito.Mockito.clearInvocations(storage);
        assertThat(posts.detail(postId, owner.getId()).thumbnailUrl()).isNull();
        assertThat(projects.detail(projectId, owner.getId()).media().getFirst().url()).isNull();
        verify(storage, org.mockito.Mockito.never()).signedUrl(anyString());
        assertThatThrownBy(() -> posts.detail(postId, null)).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> projects.detail(projectId, null)).isInstanceOf(ApplicationException.class);
    }

    @Test
    void imageErrorsAndStrictPatchUseContractStatusCodes() throws Exception {
        String bearer = "Bearer " + tokens.issueForMember(owner.getId(), Instant.now());
        String path = "/api/v1/posts/" + postId + "/thumbnail";
        multipartPut(path, new MockMultipartFile("file", "image.svg", "image/svg+xml", "<svg/>".getBytes()), bearer)
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        multipartPut(path, new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[]{(byte)255,(byte)216,(byte)255,1}), bearer)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_MEDIA"));
        multipartPut(path, new MockMultipartFile("file", "image.png", "image/png", new byte[10*1024*1024+1]), bearer)
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.code").value("MEDIA_TOO_LARGE"));
        var item = projectMedia.upload(owner.getId(), projectId, png(), MediaRole.SCREENSHOT, null, 0);
        String patchPath = "/api/v1/projects/" + projectId + "/media/" + item.id();
        for (String body : List.of("{\"altText\":\"a\",\"altText\":\"b\"}", "{} {}", "{\"storageKey\":\"bad\"}", "{\"displayOrder\":null}")) {
            mvc.perform(patch(patchPath).header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
    }

    private org.springframework.test.web.servlet.ResultActions multipartPut(String path, MockMultipartFile file, String... auth) throws Exception {
        var request = multipartPutBuilder(path, file);
        if (auth.length > 0) request.header(HttpHeaders.AUTHORIZATION, auth[0]);
        return mvc.perform(request);
    }

    private MockMultipartHttpServletRequestBuilder multipartPutBuilder(String path, MockMultipartFile... files) {
        var builder = multipart(path);
        for (MockMultipartFile file : files) builder.file(file);
        builder.with(request -> { request.setMethod("PUT"); return request; });
        return builder;
    }

    private long insertProject(long ownerId) {
        long id = TsidGenerator.generate();
        jdbc.update("insert into project(id,owner_member_id,name,lifecycle_status,visibility_status,created_at,updated_at) values (?,?,'extra media project','IN_PROGRESS','PUBLIC',now(),now())", id, ownerId);
        return id;
    }

    private long insertAndTrackProject(long ownerId) {
        long id = insertProject(ownerId);
        projectIds.add(id);
        return id;
    }

    private Member member(String suffix) {
        Member value = members.saveAndFlush(new Member("media-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8), null,
                MemberStatus.ACTIVE, null, null));
        fixtureMembers.add(value.getId());
        profiles.complete(value.getId(), "media-blog-" + value.getId(), "media-" + value.getId(), null);
        return value;
    }

    private byte[] png() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
