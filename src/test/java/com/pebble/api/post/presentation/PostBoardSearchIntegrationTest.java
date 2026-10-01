package com.pebble.api.post.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.auth.infrastructure.naver.NaverOAuthGateway;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class PostBoardSearchIntegrationTest extends AuthenticationTestSupport {
    private static final String BOARD_PATH = "/api/v1/boards";
    private static final String SEARCH_PATH = "/api/v1/posts/search";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired AccessTokenService tokens;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NaverOAuthGateway naver;
    Member author;

    @BeforeEach
    void setup() {
        Member created = members.saveAndFlush(new Member("board-search-" + UUID.randomUUID().toString().substring(0, 8),
                null, MemberStatus.ACTIVE, null, null));
        author = profiles.complete(created.getId(), "blog-" + created.getId(), "author-" + created.getId(), null);
    }

    @Test
    void boardDeletionDetachesSearchablePostAndNeverMakesHiddenPostSearchable() throws Exception {
        String boardId = createBoard("Search board");
        String publicPostId = createPost("shared board-search keyword", "PUBLIC", boardId);
        String hiddenPostId = createPost("shared board-search hidden keyword", "HIDDEN", boardId);

        mvc.perform(get(SEARCH_PATH).param("q", "board-search keyword"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(publicPostId))
                .andExpect(jsonPath("$.data.content[0].boardId").value(boardId));
        mvc.perform(get("/api/v1/members/" + author.getId() + "/boards/" + boardId + "/posts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(publicPostId));
        mvc.perform(get(SEARCH_PATH).param("q", "board-search hidden"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));

        mvc.perform(delete(BOARD_PATH + "/" + boardId).header(HttpHeaders.AUTHORIZATION, bearer(author)))
                .andExpect(status().isNoContent());
        em.clear();

        mvc.perform(get(SEARCH_PATH).param("q", "board-search keyword"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(publicPostId))
                .andExpect(jsonPath("$.data.content[0].boardId").isEmpty());
        mvc.perform(get(SEARCH_PATH).param("q", "board-search hidden"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/v1/members/" + author.getId() + "/boards/" + boardId + "/posts"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/posts").param("boardId", boardId))
                .andExpect(status().isBadRequest());
        assertBoardAssociation(publicPostId, null, "PUBLIC");
        assertBoardAssociation(hiddenPostId, null, "HIDDEN");
    }

    private String createBoard(String name) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("name", name);
        ResultActions result = mvc.perform(post(BOARD_PATH).header(HttpHeaders.AUTHORIZATION, bearer(author))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        return response(result).at("/data/id").asText();
    }

    private String createPost(String title, String visibility, String boardId) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("title", title).put("visibilityStatus", visibility);
        body.putArray("tagIds");
        if (boardId != null) body.put("boardId", boardId);
        ArrayNode blocks = body.putArray("blocks");
        blocks.add(mapper.createObjectNode().put("type", "TEXT").put("content", "search body"));
        ResultActions result = mvc.perform(post("/api/v1/posts").header(HttpHeaders.AUTHORIZATION, bearer(author))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        return response(result).at("/data/id").asText();
    }

    private void assertBoardAssociation(String postId, String expectedBoardId, String expectedVisibility) {
        var row = jdbc.queryForMap("select board_id, visibility_status from post where id=?", Long.parseLong(postId));
        org.assertj.core.api.Assertions.assertThat(row.get("board_id")).isEqualTo(expectedBoardId == null ? null : Long.valueOf(expectedBoardId));
        org.assertj.core.api.Assertions.assertThat(row.get("visibility_status").toString()).isEqualTo(expectedVisibility);
    }

    private JsonNode response(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String bearer(Member member) {
        return "Bearer " + tokens.issueForMember(member.getId(), Instant.now());
    }
}
