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

    private Map<String,Object> settings(List<String> ids) { return new java.util.LinkedHashMap<>(Map.of("sections",List.of(section("ACTIVITY",true),section("TECH_STACKS",true),section("PROJECTS",true),section("RECENT_POSTS",true)),"techStacks",List.of(),"featuredProjectIds",ids)); }
    private Map<String,Object> section(String key,boolean visible) { return Map.of("key",key,"visible",visible); }
    private String mine() { return "/api/v1/members/me/blog-home"; }
    private String path() { return "/api/v1/blogs/"+owner.getHandle()+"/home"; }
    private String bearer(Member member) { return "Bearer "+tokens.issueForMember(member.getId(),Instant.now()); }
    private Member blog() { String key=UUID.randomUUID().toString().substring(0,12);var m=new Member("writer-"+key,null,MemberStatus.ACTIVE,null,null);m.completeProfile("blog-"+key,"blog-"+key,m.getNickname(),Instant.now());return members.saveAndFlush(m); }
    private long project(Member member,String visibility) { long id=TsidGenerator.generate();jdbc.update("insert into project(id,owner_member_id,name,lifecycle_status,visibility_status,created_at,updated_at) values(?,?,?,'IN_PROGRESS',?,now(),now())",id,member.getId(),"대표 프로젝트",visibility);return id; }
    private void post(Member member,Instant published,String visibility,boolean blocked,boolean draft) { long id=TsidGenerator.generate();jdbc.update("insert into post(id,post_number,author_member_id,title,visibility_status,is_blocked,is_draft,published_at,created_at,updated_at,blocked_at,blocked_by_admin_id) values(?,?,?,?,?,?,?,?,now(),now(),?,?)",id,id,member.getId(),"활동 검증",visibility,blocked,draft,java.sql.Timestamp.from(published),blocked?java.sql.Timestamp.from(Instant.now()):null,blocked?1L:null); }
}
