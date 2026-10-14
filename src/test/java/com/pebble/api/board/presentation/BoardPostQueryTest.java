package com.pebble.api.board.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.oauth.OAuthProviderClient;
import com.pebble.api.board.application.BoardChanges;
import com.pebble.api.board.application.BoardService;
import com.pebble.api.member.application.MemberProfileService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.application.PostService;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import com.pebble.api.support.AuthenticationTestSupport;
import jakarta.persistence.EntityManager;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BoardPostQueryTest extends AuthenticationTestSupport {
    @Autowired BoardService boards;
    @Autowired PostService posts;
    @Autowired MemberRepository members;
    @Autowired MemberProfileService profiles;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean(name = "naverOAuthClient") OAuthProviderClient naver;

    @Test
    void filtersBeforePagingAndIncludesOnlyDirectBoardPostsInAuthorOrder() throws Exception {
        long owner = member();
        long root = board(owner, "root", null);
        long child = board(owner, "child", root);
        long old = post(owner, root, "PUBLIC");
        long hidden = post(owner, root, "HIDDEN");
        long deleted = post(owner, root, "PUBLIC");
        posts.delete(deleted, owner);
        long blocked = post(owner, root, "PUBLIC");
        post(owner, child, "PUBLIC");
        post(owner, null, "PUBLIC");
        long newest = post(owner, root, "PUBLIC");
        em.flush();
        jdbc.update("update post set is_blocked=true, blocked_at=current_timestamp, blocked_by_admin_id=1 where id=?", blocked);
        em.clear();
        String path = path(owner, root);
        mvc.perform(get(path).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(Long.toString(newest)))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(path).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.data.content[0].id").value(Long.toString(old)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get(path).param("sort", "displayOrder,desc"))
                .andExpect(jsonPath("$.data.content[0].id").value(Long.toString(old)));
        assertThat(jdbc.queryForObject("select board_id from post where id=?", Long.class, hidden)).isEqualTo(root);
        for (String query : new String[]{"boardId", "categoryId", "visibilityStatus", "unknown"}) {
            mvc.perform(get(path).param(query, "1")).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("page", "0", "1")).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("sort", "name,asc")).andExpect(status().isBadRequest());
        long other = member();
        mvc.perform(get(path(other, root))).andExpect(status().isNotFound());
        mvc.perform(get(path(owner, Long.MAX_VALUE))).andExpect(status().isNotFound());
    }

    @Test
    void withdrawalHidesPublicTreeAndPostsButSuspensionKeepsPublicContent() throws Exception {
        long owner = member();
        long board = board(owner, "root", null);
        post(owner, board, "PUBLIC");
        em.flush();
        jdbc.update("update member set status='SUSPENDED' where id=?", owner);
        em.clear();
        mvc.perform(get(path(owner, board))).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        jdbc.update("update member set status='WITHDRAWAL_PENDING', withdrawal_requested_at=current_timestamp, "
                + "withdrawal_scheduled_at=current_timestamp+interval '7 days' where id=?", owner);
        em.clear();
        mvc.perform(get(path(owner, board))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/members/" + owner + "/boards")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/members/" + Long.MAX_VALUE + "/boards")).andExpect(status().isNotFound());
    }

    @Test
    void deletedBoardCannotBeBrowsedAndDetachedPostsRemainPublic() throws Exception {
        long owner = member();
        long board = board(owner, "root", null);
        long post = post(owner, board, "PUBLIC");
        boards.delete(board, owner);
        em.clear();
        mvc.perform(get(path(owner, board))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/posts/" + post)).andExpect(status().isOk()).andExpect(jsonPath("$.data.boardId").isEmpty());
        mvc.perform(get("/api/v1/members/" + owner + "/boards")).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void corsAllowsOnlyDocumentedOriginsAndMethods() throws Exception {
        long owner = member();
        long board = board(owner, "root", null);
        for (String path : new String[]{"/api/v1/members/me/boards", "/api/v1/members/" + owner + "/boards", path(owner, board)}) {
            mvc.perform(options(path).header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        }
        mvc.perform(options("/api/v1/boards").header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
        mvc.perform(options("/api/v1/boards/" + board).header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isOk());
        mvc.perform(options(path(owner, board)).header("Origin", "https://untrusted.example").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        mvc.perform(options(path(owner, board)).header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    private long member() {
        Member member = members.saveAndFlush(new Member("board-query-" + UUID.randomUUID().toString().substring(0, 8), null, MemberStatus.ACTIVE, null, null));
        profiles.complete(member.getId(), "blog-" + member.getId(), "query-" + member.getId(), null);
        return member.getId();
    }
    private long board(long owner, String name, Long parent) {
        return boards.create(owner, new BoardChanges(Set.of("name", "parentId"), name, parent, 0)).getId();
    }
    private long post(long owner, Long board, String visibility) throws Exception {
        var json = mapper.createObjectNode().put("title", "title").put("visibilityStatus", visibility);
        if (board != null) json.put("boardId", board.toString());
        json.putArray("blocks").addObject().put("type", "TEXT").put("content", "content");
        return posts.create(owner, PostWriteRequest.parse(json, true)).post().getId();
    }
    private String path(long owner, long board) { return "/api/v1/members/" + owner + "/boards/" + board + "/posts"; }
}
