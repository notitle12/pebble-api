package com.pebble.api.insight.application;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.insight.application.ContentInsightsService.*;
import com.pebble.api.like.infrastructure.persistence.*;
import com.pebble.api.member.domain.*;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.domain.*;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.project.domain.*;
import com.pebble.api.project.infrastructure.persistence.ProjectRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContentInsightSortingTest extends AuthenticationTestSupport {
    @Autowired ContentInsightsService insights;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired ProjectRepository projects;
    @Autowired PostLikeRepository postLikes;
    @Autowired ProjectLikeRepository projectLikes;
    @Autowired AccessTokenService tokens;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    Member owner;
    Post oldest,middle,newest;
    @BeforeEach void setup() {
        owner=member();
        oldest=post("Old",1,"2026-01-01T00:00:00Z");
        middle=post("Middle",2,"2026-02-01T00:00:00Z");
        newest=post("New",3,"2026-03-01T00:00:00Z");
        // ID와 날짜의 순서를 다르게 하여 실제 작성일 정렬을 검증한다.
        jdbc.update("update post set created_at=? where id=?",java.sql.Timestamp.from(Instant.parse("2026-04-01T00:00:00Z")),middle.getId());
        for(int n=0;n<3;n++) insights.record(Kind.POST,newest.getId(),"v"+n,null);
        insights.record(Kind.POST,oldest.getId(),"v",null);
    }
    Member member() {
        String key=UUID.randomUUID().toString().substring(0,12);
        var member=agreedMember(new Member("s-"+key,null,MemberStatus.ACTIVE,null,null));
        member.completeProfile("Blog "+key,"s-"+key,member.getNickname(),Instant.now());
        return members.saveAndFlush(member);
    }
    Post post(String title,int number,String time) {
        var p=posts.saveAndFlush(new Post(owner,null,title,null,PostVisibility.PUBLIC,null,number));
        jdbc.update("update post set created_at=? where id=?",java.sql.Timestamp.from(Instant.parse(time)),p.getId());
        return p;
    }
    List<String> ids(ContentList list) {return list.items().stream().map(Item::id).toList();}
    @Test void latestAndViewsSupportBothDirectionsAndZeroViews() {
        assertThat(ids(insights.list(owner.getId(),Kind.POST,0,"",ListSort.LATEST,Direction.DESC))).containsExactly(middle.getId().toString(),newest.getId().toString(),oldest.getId().toString());
        assertThat(ids(insights.list(owner.getId(),Kind.POST,0,"",ListSort.LATEST,Direction.ASC))).containsExactly(oldest.getId().toString(),newest.getId().toString(),middle.getId().toString());
        var descending=insights.list(owner.getId(),Kind.POST,0,"",ListSort.VIEWS,Direction.DESC);
        assertThat(ids(descending)).containsExactly(newest.getId().toString(),oldest.getId().toString(),middle.getId().toString());
        assertThat(descending.likeCounts()).isEmpty();
        assertThat(ids(insights.list(owner.getId(),Kind.POST,0,"",ListSort.VIEWS,Direction.ASC))).containsExactly(middle.getId().toString(),oldest.getId().toString(),newest.getId().toString());
    }
    @Test void popularUsesActiveLikesAndExcludesCancelledAndWithdrawnVotes() {
        var first=member();var second=member();var withdrawn=member();
        postLikes.register(oldest.getId(),first.getId());postLikes.register(oldest.getId(),second.getId());
        postLikes.register(newest.getId(),first.getId());
        postLikes.register(middle.getId(),first.getId());postLikes.cancel(middle.getId(),first.getId());
        postLikes.register(middle.getId(),withdrawn.getId());withdrawn.requestWithdrawal(Instant.now());members.saveAndFlush(withdrawn);
        var list=insights.list(owner.getId(),Kind.POST,0,"",ListSort.POPULAR,Direction.DESC);
        assertThat(ids(list)).containsExactly(oldest.getId().toString(),newest.getId().toString(),middle.getId().toString());
        assertThat(list.likeCounts()).containsEntry(oldest.getId().toString(),2L).containsEntry(newest.getId().toString(),1L).containsEntry(middle.getId().toString(),0L);
        assertThat(ids(insights.list(owner.getId(),Kind.POST,0,"",ListSort.POPULAR,Direction.ASC))).containsExactly(middle.getId().toString(),newest.getId().toString(),oldest.getId().toString());
    }
    @Test void tiesHaveStablePagesAndSearchIsOwnerScoped() {
        for(int n=4;n<=15;n++) post("Tie "+n,n,"2026-05-01T00:00:00Z");
        var other=member();posts.saveAndFlush(new Post(other,null,"Tie foreign",null,PostVisibility.PUBLIC,null,1));
        var first=insights.list(owner.getId(),Kind.POST,0,"Tie",ListSort.VIEWS,Direction.ASC);
        var second=insights.list(owner.getId(),Kind.POST,1,"Tie",ListSort.VIEWS,Direction.ASC);
        assertThat(first.totalElements()).isEqualTo(12);assertThat(first.items()).hasSize(10);assertThat(second.items()).hasSize(2);
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
        var both=new ArrayList<String>(ids(first));both.addAll(ids(second));
        assertThat(both.stream().map(Long::parseLong).toList()).isSorted();
        assertThat(insights.list(owner.getId(),Kind.POST,0,"%_",ListSort.POPULAR,Direction.DESC).items()).isEmpty();
    }
    @Test void projectSortsIncludeUnviewedHiddenContent() {
        var p=projects.saveAndFlush(new Project(owner,"Hidden",null,null,null,null,ProjectLifecycleStatus.IN_PROGRESS,null,null,ProjectVisibility.HIDDEN));
        var q=projects.saveAndFlush(new Project(owner,"Visible",null,null,null,null,ProjectLifecycleStatus.IN_PROGRESS,null,null,ProjectVisibility.PUBLIC));
        projectLikes.register(q.getId(),member().getId());insights.record(Kind.PROJECT,q.getId(),"v",null);
        assertThat(ids(insights.list(owner.getId(),Kind.PROJECT,0,"",ListSort.VIEWS,Direction.ASC))).containsExactly(p.getId().toString(),q.getId().toString());
        var ranked=insights.list(owner.getId(),Kind.PROJECT,0,"",ListSort.POPULAR,Direction.DESC);
        assertThat(ranked.likeCounts()).containsEntry(q.getId().toString(),1L).containsEntry(p.getId().toString(),0L);
    }
    @Test void controllerDefaultsAndRejectsUnknownOrDuplicateSorts() throws Exception {
        String token="Bearer "+tokens.issueForMember(owner.getId(),Instant.now());
        String path="/api/v1/members/me/content-insights/items";
        mvc.perform(get(path).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.sort").value("LATEST")).andExpect(jsonPath("$.data.direction").value("DESC"));
        mvc.perform(get(path).header("Authorization",token).param("sort","VIEWS").param("direction","ASC"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(middle.getId().toString()));
        for(String[] p:new String[][]{{"sort","created_at"},{"sort","views"},{"direction","asc"},{"direction","DESC;DROP TABLE post"}})
            mvc.perform(get(path).header("Authorization",token).param(p[0],p[1])).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization",token).param("sort","VIEWS","POPULAR")).andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization",token).param("direction","ASC","DESC")).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("sort","POPULAR")).andExpect(status().isUnauthorized());
    }
}
