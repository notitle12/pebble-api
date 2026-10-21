package com.pebble.api.member.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.auth.application.AccessTokenService;
import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BlogHomeIntegrationTest extends AuthenticationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired AccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired jakarta.persistence.EntityManager entityManager;
    Member owner, other;
    @BeforeEach void setup() { owner = blog(); other = blog(); }

    @Test void defaultsAndOwnerSettingsArePersistentAndIsolated() throws Exception {
        mvc.perform(get(path())).andExpect(status().isOk()).andExpect(jsonPath("$.data.sections[0].key").value("ACTIVITY"))
                .andExpect(jsonPath("$.data.sections.length()").value(4)).andExpect(jsonPath("$.data.techStacks.length()").value(0));
        var settings = settings(List.of());
        settings.put("techStacks", List.of(" Java ", "스프링"));
        settings.put("sections", List.of(section("PROJECTS", true), section("RECENT_POSTS", true), section("TECH_STACKS", false), section("ACTIVITY", true)));
        mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(settings)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.techStacks[0]").value("Java"));
        mvc.perform(get(path())).andExpect(jsonPath("$.data.sections[0].key").value("PROJECTS"))
                .andExpect(jsonPath("$.data.sections[2].visible").value(false));
        mvc.perform(get(mine()).header("Authorization", bearer(other))).andExpect(jsonPath("$.data.techStacks.length()").value(0));
        assertThat(jdbc.queryForObject("select count(*) from blog_home_settings where member_id=?", Integer.class, owner.getId())).isEqualTo(1);
    }

    @Test void rejectsUnauthenticatedAndMalformedOrAmbiguousInputs() throws Exception {
        mvc.perform(get(mine())).andExpect(status().isUnauthorized());
        mvc.perform(put(mine()).contentType("application/json").content(mapper.writeValueAsString(settings(List.of())))).andExpect(status().isForbidden());
        for (String json : List.of("null", "{}", "[]", "{}{}", "{\"sections\":[],\"sections\":[]}",
                mapper.writeValueAsString(Map.of("sections", List.of(section("ACTIVITY", true)), "techStacks", List.of(), "featuredProjectIds", List.of())),
                mapper.writeValueAsString(Map.of("sections", List.of(section("ACTIVITY", true), section("ACTIVITY", true), section("PROJECTS", true), section("RECENT_POSTS", true)), "techStacks", List.of(), "featuredProjectIds", List.of())))) {
            mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(json)).andExpect(status().isBadRequest());
        }
        for (List<String> names : List.of(List.of("Java", "java"), List.of("x".repeat(51)), List.of("bad\nname"), java.util.Collections.nCopies(21, "x"))) {
            var input = settings(List.of()); input.put("techStacks", names);
            mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest());
        }
        for (List<String> ids : List.of(List.of("01"), List.of("1", "1"), List.of("9223372036854775808"))) {
            mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(settings(ids)))).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path()+"?year=2020")).andExpect(status().isBadRequest());
        mvc.perform(get(path()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        var extra=settings(List.of());extra.put("memberId",other.getId().toString());
        mvc.perform(put(mine()).header("Authorization",bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(extra))).andExpect(status().isBadRequest());
    }

    @Test void onlyOwnPublicProjectsCanBeChosenAndLaterHiddenProjectsDisappear() throws Exception {
        long own = project(owner, "PUBLIC"), foreign = project(other, "PUBLIC"), hidden = project(owner, "HIDDEN");
        for (long id : List.of(foreign, hidden, Long.MAX_VALUE)) {
            mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(settings(List.of(Long.toString(id)))))).andExpect(status().isNotFound());
        }
        mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(settings(List.of(Long.toString(own)))))).andExpect(status().isOk());
        mvc.perform(get(path())).andExpect(jsonPath("$.data.featuredProjectIds[0]").value(Long.toString(own)));
        jdbc.update("update project set visibility_status='HIDDEN' where id=?", own);
        entityManager.clear();
        mvc.perform(get(path())).andExpect(jsonPath("$.data.featuredProjectIds.length()").value(0));
        mvc.perform(get(mine()).header("Authorization", bearer(owner))).andExpect(jsonPath("$.data.featuredProjectIds[0]").value(Long.toString(own)));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void unavailableFeaturedProjectsDoNotFailHomeAtRealTransactionBoundary() throws Exception {
        try {
            long visible = project(owner, "PUBLIC"), hidden = project(owner, "PUBLIC"), deleted = project(owner, "PUBLIC");
            mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json")
                    .content(mapper.writeValueAsString(settings(List.of("" + visible, "" + hidden, "" + deleted)))))
                    .andExpect(status().isOk());
            jdbc.update("update project set visibility_status='HIDDEN' where id=?", hidden);
            jdbc.update("update project set visibility_status='DELETED' where id=?", deleted);
            mvc.perform(get(path())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.featuredProjectIds.length()").value(1))
                    .andExpect(jsonPath("$.data.featuredProjectIds[0]").value("" + visible))
                    .andExpect(jsonPath("$.data.sections.length()").value(4))
                    .andExpect(jsonPath("$.data.activity.timeZone").value("Asia/Seoul"));
            jdbc.update("delete from project where id=?", deleted);
            mvc.perform(get(path())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.featuredProjectIds.length()").value(1));
            mvc.perform(get(mine()).header("Authorization", bearer(owner)))
                    .andExpect(jsonPath("$.data.featuredProjectIds.length()").value(3));
        } finally {
            jdbc.update("delete from project where owner_member_id in (?,?)", owner.getId(), other.getId());
            jdbc.update("delete from member where id in (?,?)", owner.getId(), other.getId());
        }
    }

    @Test void activityUsesSeoulPublicationDatesAndExcludesPrivateBlockedDraftOtherAndExpiredPosts() throws Exception {
        var zone=ZoneId.of("Asia/Seoul");var today=LocalDate.now(zone);var from=today.minusYears(1).plusDays(1);
        Instant first=today.atStartOfDay(zone).toInstant();
        post(owner, first, "PUBLIC", false, false);
        post(owner, first.plusSeconds(1), "PUBLIC", false, false);
        post(owner, first.minusSeconds(1), "PUBLIC", false, false);
        post(owner, from.atStartOfDay(zone).toInstant(), "PUBLIC", false, false);
        post(owner, from.atStartOfDay(zone).toInstant().minusSeconds(1), "PUBLIC", false, false);
        post(owner, first, "HIDDEN", false, false);
        post(owner, first, "HIDDEN", false, true);
        post(owner, first, "DELETED", false, false);
        post(owner, first, "PUBLIC", true, false);
        post(other, first, "PUBLIC", false, false);
        mvc.perform(get(path())).andExpect(status().isOk()).andExpect(jsonPath("$.data.activity.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.activity.from").value(from.toString())).andExpect(jsonPath("$.data.activity.to").value(today.toString()))
                .andExpect(jsonPath("$.data.activity.days.length()").value(3)).andExpect(jsonPath("$.data.activity.days[2].date").value(today.toString()))
                .andExpect(jsonPath("$.data.activity.days[2].count").value(2));
    }

    @Test void pendingWithdrawalHidesEntireHomeAndDeletionCascadesSettings() throws Exception {
        mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(settings(List.of())))).andExpect(status().isOk());
        owner.requestWithdrawal(Instant.now());members.saveAndFlush(owner);
        mvc.perform(get(path())).andExpect(status().isNotFound());
        jdbc.update("delete from member where id=?",owner.getId());
        assertThat(jdbc.queryForObject("select count(*) from blog_home_settings where member_id=?",Integer.class,owner.getId())).isZero();
    }

    @Test void stonesMergeRecordsWithoutDuplicationAndProtectVersionsAndOwnership() throws Exception {
        long first=post(owner,Instant.now(),"PUBLIC",false,false),second=post(owner,Instant.now(),"PUBLIC",false,false),foreign=post(other,Instant.now(),"PUBLIC",false,false);
        String mineStones="/api/v1/members/me/blog-stones";
        var merged=Map.of("id","stone-a","postIds",List.of(Long.toString(first),Long.toString(second)),"x",500,"y",300,"rotation",10);
        mvc.perform(get(mineStones)).andExpect(status().isUnauthorized());
        mvc.perform(put(mineStones).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get(mineStones).header("Authorization",bearer(owner))).andExpect(jsonPath("$.data.version").value(0));
        String body=mapper.writeValueAsString(Map.of("version",0,"stones",List.of(merged)));
        mvc.perform(put(mineStones).header("Authorization",bearer(owner)).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.stones[0].postIds.length()").value(2));
        mvc.perform(put(mineStones).header("Authorization",bearer(owner)).contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(get(path().replace("/home","/stones"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.posts.length()").value(2));
        mvc.perform(get(mineStones).header("Authorization",bearer(other))).andExpect(jsonPath("$.data.stones.length()").value(0));
        var bad=new java.util.LinkedHashMap<String,Object>(merged);bad.put("postIds",List.of(Long.toString(foreign)));
        mvc.perform(put(mineStones).header("Authorization",bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(Map.of("version",1,"stones",List.of(bad))))).andExpect(status().isNotFound());
        bad.put("id","stone-b");bad.put("postIds",List.of(Long.toString(first)));
        mvc.perform(put(mineStones).header("Authorization",bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(Map.of("version",1,"stones",List.of(merged,bad))))).andExpect(status().isBadRequest());
        for(Object invalid:List.of(-1,1001,"500")){bad.put("x",invalid);mvc.perform(put(mineStones).header("Authorization",bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(Map.of("version",1,"stones",List.of(bad))))).andExpect(status().isBadRequest());}
        jdbc.update("update post set visibility_status='HIDDEN' where id=?",first);
        mvc.perform(get(path().replace("/home","/stones"))).andExpect(jsonPath("$.data.stones[0].postIds.length()").value(1)).andExpect(jsonPath("$.data.posts[0].id").value(Long.toString(second)));
        jdbc.update("update post set visibility_status='DELETED' where id=?",second);
        mvc.perform(get(path().replace("/home","/stones"))).andExpect(jsonPath("$.data.stones.length()").value(0)).andExpect(jsonPath("$.data.posts.length()").value(0));
    }

    @Test void stoneInventoryAndDisplayChoiceStayPrivateAndCascadeOnWithdrawal() throws Exception {
        post(owner,Instant.now(),"PUBLIC",false,false);post(owner,Instant.now(),"HIDDEN",false,false);post(other,Instant.now(),"PUBLIC",false,false);
        String inventory="/api/v1/members/me/blog-stones/posts";
        mvc.perform(get(inventory).header("Authorization",bearer(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1)).andExpect(jsonPath("$.data.hasNext").value(false));
        for(String query:List.of("?page=-1","?page=0&page=1","?page=01","?memberId=1"))mvc.perform(get(inventory+query).header("Authorization",bearer(owner))).andExpect(status().isBadRequest());
        var input=settings(List.of());input.put("activityDisplay","STONES");
        mvc.perform(put(mine()).header("Authorization",bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andExpect(jsonPath("$.data.activityDisplay").value("STONES"));
        mvc.perform(get(path())).andExpect(jsonPath("$.data.activityDisplay").value("STONES"));
        input.put("activityDisplay","INVALID");mvc.perform(put(mine()).header("Authorization",bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/members/me/blog-stones").header("Authorization",bearer(owner)).contentType("application/json").content("{\"version\":0,\"stones\":[]}")).andExpect(status().isOk());
        owner.requestWithdrawal(Instant.now());members.saveAndFlush(owner);
        mvc.perform(get(path().replace("/home","/stones"))).andExpect(status().isNotFound());
        jdbc.update("delete from post where author_member_id=?",owner.getId());
        jdbc.update("delete from member where id=?",owner.getId());
        assertThat(jdbc.queryForObject("select count(*) from blog_stone_layout where member_id=?",Integer.class,owner.getId())).isZero();
    }

    @Test void gardenCountsFirstPublicationOnceAndKeepsRecordsAfterDeletion() throws Exception {
        String auth=bearer(owner),endpoint="/api/v1/members/me/stone-garden";
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).header("Authorization",auth)).andExpect(jsonPath("$.data.publicationCount").value(0));
        var draft=mapper.createObjectNode().put("title","first").put("visibilityStatus","HIDDEN").put("draft",true);
        draft.putArray("blocks").addObject().put("type","TEXT").put("content","record");
        var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/posts").header("Authorization",auth).contentType("application/json").content(draft.toString())).andExpect(status().isCreated()).andReturn();
        String id=mapper.readTree(result.getResponse().getContentAsString()).at("/data/id").asText();
        mvc.perform(get(endpoint).header("Authorization",auth)).andExpect(jsonPath("$.data.publicationCount").value(0));
        for(String content:List.of("{\"draft\":false,\"visibilityStatus\":\"PUBLIC\"}","{\"title\":\"edited\"}","{\"visibilityStatus\":\"HIDDEN\"}","{\"visibilityStatus\":\"PUBLIC\"}"))
            mvc.perform(patch("/api/v1/posts/"+id).header("Authorization",auth).contentType("application/json").content(content)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/posts/"+id).header("Authorization",auth)).andExpect(status().isNoContent());
        mvc.perform(get(endpoint).header("Authorization",auth)).andExpect(jsonPath("$.data.publicationCount").value(1));
        mvc.perform(get(path().replace("/home","/stone-garden"))).andExpect(jsonPath("$.data.publicationCount").value(1));
        mvc.perform(get(endpoint).header("Authorization",bearer(other))).andExpect(jsonPath("$.data.publicationCount").value(0));
        mvc.perform(get(endpoint+"?count=1000").header("Authorization",auth)).andExpect(status().isBadRequest());
        jdbc.update("delete from post where id=?",Long.parseLong(id));
        jdbc.update("delete from member where id=?",owner.getId());
        assertThat(jdbc.queryForObject("select count(*) from post_publication_history where member_id=?",Long.class,owner.getId())).isZero();
    }

    @Test void featuredProjectManagementPreservesPreferencesAndStaleChoicesDoNotBlockSaving() throws Exception {
        long stale = project(owner, "PUBLIC"), first = project(owner, "PUBLIC"), second = project(owner, "PUBLIC");
        var input = settings(List.of("" + stale, "" + first, "" + second));
        mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.featuredProjectLimit").value(6));
        jdbc.update("update project set visibility_status='DELETED' where id=?", stale);
        entityManager.clear();
        input.put("featuredProjectLimit", 1);
        input.put("techStacks", List.of("Java"));
        mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.featuredProjectIds.length()").value(2));
        mvc.perform(get(path())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.featuredProjectIds.length()").value(1))
                .andExpect(jsonPath("$.data.featuredProjectIds[0]").value("" + first));
        long added = project(owner, "PUBLIC");
        String endpoint = "/api/v1/members/me/projects/" + added + "/featured";
        mvc.perform(put(endpoint).header("Authorization", bearer(owner)).contentType("application/json").content("{\"featured\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.featuredProjectIds.length()").value(3))
                .andExpect(jsonPath("$.data.featuredProjectLimit").value(1)).andExpect(jsonPath("$.data.techStacks[0]").value("Java"));
        mvc.perform(put(endpoint).header("Authorization", bearer(owner)).contentType("application/json").content("{\"featured\":true}"))
                .andExpect(jsonPath("$.data.featuredProjectIds.length()").value(3));
        mvc.perform(put(endpoint).header("Authorization", bearer(owner)).contentType("application/json").content("{\"featured\":false}"))
                .andExpect(jsonPath("$.data.featuredProjectIds.length()").value(2));
        long foreign = project(other, "PUBLIC");
        mvc.perform(put("/api/v1/members/me/projects/" + foreign + "/featured").header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"featured\":true}")).andExpect(status().isNotFound());
        for (Object limit : List.of(0, 7, "2", 1.5)) {
            input.put("featuredProjectLimit", limit);
            mvc.perform(put(mine()).header("Authorization", bearer(owner)).contentType("application/json").content(mapper.writeValueAsString(input)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void popularityRanksAllPublicContentAndExcludesUnavailableAndCancelledLikes() throws Exception {
        var now = Instant.now();
        long oldPost = post(owner, now.minusSeconds(86400 * 30), "PUBLIC", false, false);
        long newPost = post(owner, now, "PUBLIC", false, false);
        long hiddenPost = post(owner, now, "HIDDEN", false, false);
        long blockedPost = post(owner, now, "PUBLIC", true, false);
        post(owner, now, "PUBLIC", false, false);
        long oldProject = project(owner, "PUBLIC"), newProject = project(owner, "PUBLIC");
        long hiddenProject = project(owner, "HIDDEN");
        project(owner, "PUBLIC");
        jdbc.update("update project set published_at=? where id=?", java.sql.Timestamp.from(now.minusSeconds(86400 * 30)), oldProject);
        jdbc.update("update project set published_at=? where id=?", java.sql.Timestamp.from(now), newProject);
        for (String kind : List.of("post", "project")) {
            long oldId = kind.equals("post") ? oldPost : oldProject;
            long newId = kind.equals("post") ? newPost : newProject;
            long hiddenId = kind.equals("post") ? hiddenPost : hiddenProject;
            for (long contentId : List.of(oldId, hiddenId)) {
                for (Member voter : List.of(owner, other)) {
                    jdbc.update("insert into " + kind + "_like(id," + kind + "_id,member_id,created_at) values(?,?,?,now())",
                            TsidGenerator.generate(), contentId, voter.getId());
                }
            }
            jdbc.update("insert into " + kind + "_like(id," + kind + "_id,member_id,created_at) values(?,?,?,now())",
                    TsidGenerator.generate(), newId, owner.getId());
            if (kind.equals("post")) jdbc.update("insert into post_like(id,post_id,member_id,created_at) values(?,?,?,now())",
                    TsidGenerator.generate(), blockedPost, owner.getId());
            String endpoint = "/api/v1/" + kind + "s/popular";
            mvc.perform(get(endpoint).param("size", "1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value("" + oldId))
                    .andExpect(jsonPath("$.data.content[0].likeCount").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.hasNext").value(true));
            mvc.perform(get(endpoint).param("size", "1").param("page", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value("" + newId));
            jdbc.update("update " + kind + "_like set deleted_at=now() where " + kind + "_id=? and member_id=?", oldId, other.getId());
            mvc.perform(get(endpoint)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].id").value("" + newId));
            mvc.perform(get(endpoint).param("sort", "createdAt,desc")).andExpect(status().isBadRequest());
        }
    }

    private Map<String,Object> settings(List<String> ids) { return new java.util.LinkedHashMap<>(Map.of("sections",List.of(section("ACTIVITY",true),section("TECH_STACKS",true),section("PROJECTS",true),section("RECENT_POSTS",true)),"techStacks",List.of(),"featuredProjectIds",ids)); }
    private Map<String,Object> section(String key,boolean visible) { return Map.of("key",key,"visible",visible); }
    private String mine() { return "/api/v1/members/me/blog-home"; }
    private String path() { return "/api/v1/blogs/"+owner.getHandle()+"/home"; }
    private String bearer(Member member) { return "Bearer "+tokens.issueForMember(member.getId(),Instant.now()); }
    private Member blog() { String key=UUID.randomUUID().toString().substring(0,12);var m=agreedMember(new Member("writer-"+key,null,MemberStatus.ACTIVE,null,null));m.completeProfile("blog-"+key,"blog-"+key,m.getNickname(),Instant.now());return members.saveAndFlush(m); }
    private long project(Member member,String visibility) { long id=TsidGenerator.generate();jdbc.update("insert into project(id,owner_member_id,name,lifecycle_status,visibility_status,created_at,updated_at) values(?,?,?,'IN_PROGRESS',?,now(),now())",id,member.getId(),"대표 프로젝트",visibility);return id; }
    private long post(Member member,Instant published,String visibility,boolean blocked,boolean draft) { long id=TsidGenerator.generate();jdbc.update("insert into post(id,post_number,author_member_id,title,visibility_status,is_blocked,is_draft,published_at,created_at,updated_at,blocked_at,blocked_by_admin_id) values(?,?,?,?,?,?,?,?,now(),now(),?,?)",id,id,member.getId(),"활동 검증",visibility,blocked,draft,java.sql.Timestamp.from(published),blocked?java.sql.Timestamp.from(Instant.now()):null,blocked?1L:null); return id; }
}
