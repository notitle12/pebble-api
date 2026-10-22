package com.pebble.api.update.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pebble.api.admin.domain.*;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.*;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ServiceUpdateIntegrationTest extends AuthenticationTestSupport {
    private static final String ADMIN = "/api/v1/admin/updates";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AdminAccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired AdminSessionService sessions;
    @Autowired AdminRefreshTokenService refreshTokens;
    @Autowired AccessTokenService accessTokens;
    private AdminAccount manager;
    private String token;
    @BeforeEach void setup() {
        manager = accounts.saveAndFlush(new AdminAccount("updates-"+UUID.randomUUID(), passwords.encode("Updates-Test-2026"), AdminRole.MANAGER));
        token = sessions.login(manager.getLoginId(), "Updates-Test-2026").tokens().accessToken();
    }
    @AfterEach void cleanup() { refreshTokens.revokeAll(manager.getId()); }

    @Test void draftPublicationEditWithdrawalAndDeletionRespectPublicBoundary() throws Exception {
        JsonNode created = create("DRAFT");
        String id = created.path("id").asText();
        assertThat(id).matches("[1-9][0-9]+");
        assertThat(created.path("revision").asLong()).isZero();
        mvc.perform(get("/api/v1/updates/"+id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/updates")).andExpect(jsonPath("$.data.totalElements").value(0));
        ObjectNode publish = body("PUBLISHED").put("expectedRevision",0);
        JsonNode published = data(mvc.perform(put(ADMIN+"/"+id).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(publish.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1)));
        String time = published.path("publishedAt").asText();
        mvc.perform(get("/api/v1/updates/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.body").value("Line one\nLine two <script>safe text</script>"))
            .andExpect(jsonPath("$.data.revision").doesNotExist()).andExpect(jsonPath("$.data.status").doesNotExist());
        mvc.perform(get("/api/v1/updates").param("type","FIX")).andExpect(jsonPath("$.data.items[0].id").value(id)).andExpect(jsonPath("$.data.items[0].body").doesNotExist());
        mvc.perform(get("/api/v1/updates").param("type","FEATURE")).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(put(ADMIN+"/"+id).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(body("DRAFT").put("expectedRevision",1).toString()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/updates/"+id)).andExpect(status().isNotFound());
        mvc.perform(put(ADMIN+"/"+id).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(body("PUBLISHED").put("expectedRevision",2).toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.publishedAt").value(time));
        mvc.perform(delete(ADMIN+"/"+id).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":3}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/updates/"+id)).andExpect(status().isNotFound());
        mvc.perform(get(ADMIN+"/"+id).header("Authorization",bearer())).andExpect(status().isNotFound());
        mvc.perform(get(ADMIN).header("Authorization",bearer())).andExpect(jsonPath("$.data.totalElements").value(0));
    }
    @Test void staleRevisionCannotOverwriteOrDeleteNewerChanges() throws Exception {
        String id = create("PUBLISHED").path("id").asText();
        mvc.perform(put(ADMIN+"/"+id).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(body("PUBLISHED").put("title","New title").put("expectedRevision",0).toString()))
            .andExpect(status().isOk());
        mvc.perform(put(ADMIN+"/"+id).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(body("DRAFT").put("expectedRevision",0).toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("UPDATE_CONFLICT"));
        mvc.perform(delete(ADMIN+"/"+id).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":0}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/updates/"+id)).andExpect(jsonPath("$.data.title").value("New title"));
    }
    @Test void strictInputRejectsUnknownDuplicateTrailingFieldsAndInvalidLimits() throws Exception {
        for (String invalid : new String[]{body("DRAFT").put("title","").toString(), body("DRAFT").put("title","x".repeat(161)).toString(),
            body("DRAFT").put("body","x".repeat(20001)).toString(), body("DRAFT").put("type","NEWS").toString(),
            body("UNKNOWN").toString(), body("DRAFT").put("summary","x".repeat(301)).toString(),
            body("DRAFT").put("version","x".repeat(41)).toString(), body("DRAFT").put("title","\u0000").toString(),
            body("DRAFT").put("unexpected",true).toString(), body("DRAFT").toString()+" {}",
            body("DRAFT").toString().replace("\"title\":", "\"title\":\"duplicate\",\"title\":")}) {
            mvc.perform(post(ADMIN).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        String id = create("DRAFT").path("id").asText();
        mvc.perform(put(ADMIN+"/"+id).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(body("DRAFT").toString())).andExpect(status().isBadRequest());
        mvc.perform(delete(ADMIN+"/"+id).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":-1}"))
            .andExpect(status().isBadRequest());
    }
    @Test void onlyActiveAdministratorsWithLiveSessionsCanWrite() throws Exception {
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        mvc.perform(post(ADMIN).contentType(MediaType.APPLICATION_JSON).content(body("DRAFT").toString())).andExpect(status().isForbidden());
        String user = accessTokens.issueForMember(999, Instant.now());
        mvc.perform(post(ADMIN).header("Authorization","Bearer "+user).contentType(MediaType.APPLICATION_JSON).content(body("DRAFT").toString())).andExpect(status().isForbidden());
        refreshTokens.revokeAll(manager.getId());
        mvc.perform(post(ADMIN).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(body("DRAFT").toString())).andExpect(status().isUnauthorized());
    }
    @Test void masterCanManageAndPublicListingHasDeterministicPages() throws Exception {
        AdminAccount master = accounts.saveAndFlush(new AdminAccount("updates-master-"+UUID.randomUUID(), passwords.encode("Updates-Test-2026"), AdminRole.MASTER));
        String masterToken = sessions.login(master.getLoginId(),"Updates-Test-2026").tokens().accessToken();
        try {
            create("PUBLISHED");
            String latest = create("PUBLISHED").path("id").asText();
            mvc.perform(get("/api/v1/updates").param("size","1")).andExpect(jsonPath("$.data.items[0].id").value(latest))
                .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.totalPages").value(2));
            mvc.perform(get("/api/v1/updates").param("size","1").param("page","1")).andExpect(jsonPath("$.data.items.length()").value(1));
            mvc.perform(get(ADMIN).header("Authorization","Bearer "+masterToken).param("status","PUBLISHED")).andExpect(status().isOk());
        } finally { refreshTokens.revokeAll(master.getId()); }
    }
    @Test void validatesQueriesIdsAndExactCorsMethods() throws Exception {
        for (String[] query : new String[][]{{"page","-1"},{"page","100001"},{"size","51"},{"size","0"},{"type","BAD"},{"status","DRAFT"},{"sort","title"}}) {
            mvc.perform(get("/api/v1/updates").param(query[0],query[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/updates").param("page","0","1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/updates/01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/updates/9223372036854775808")).andExpect(status().isBadRequest());
        mvc.perform(options(ADMIN+"/123").header("Origin","http://localhost:3000").header("Access-Control-Request-Method","PUT").header("Access-Control-Request-Headers","authorization,content-type"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000"));
        mvc.perform(options(ADMIN+"/123").header("Origin","https://untrusted.example").header("Access-Control-Request-Method","PUT"))
            .andExpect(status().isForbidden());
    }
    private ObjectNode body(String status) {
        return mapper.createObjectNode().put("title","Update").put("summary","Summary").put("body","Line one\nLine two <script>safe text</script>")
            .put("type","FIX").putNull("version").put("status",status);
    }
    private String bearer() { return "Bearer "+token; }
    private JsonNode create(String status) throws Exception {
        return data(mvc.perform(post(ADMIN).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(body(status).toString()))
            .andExpect(status().isCreated()).andExpect(header().exists("Location")));
    }
    private JsonNode data(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data"); }
}
