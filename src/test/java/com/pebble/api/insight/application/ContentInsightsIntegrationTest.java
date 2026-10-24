package com.pebble.api.insight.application;

import static org.assertj.core.api.Assertions.*;
import com.pebble.api.insight.application.ContentInsightsService.Kind;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ContentInsightsIntegrationTest extends AuthenticationTestSupport {
    @Autowired ContentInsightsService insights;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired ProjectRepository projects;
    @Autowired JdbcTemplate jdbc;
    Member owner;
    Post post;
    Project project;
    final LocalDate today=LocalDate.now(ContentInsightsService.SEOUL);
    @BeforeEach void setup() {
        owner=member();
        post=posts.saveAndFlush(new Post(owner,null,"Post",null,PostVisibility.PUBLIC,"post",1));
        project=projects.saveAndFlush(new Project(owner,"Project",null,null,null,null,ProjectLifecycleStatus.IN_PROGRESS,null,null,ProjectVisibility.PUBLIC));
    }
    Member member() {
        String key=UUID.randomUUID().toString().substring(0,12);
        var m=agreedMember(new Member("i-"+key,null,MemberStatus.ACTIVE,null,null));
        m.completeProfile("Blog "+key,"i-"+key,m.getNickname(),Instant.now());
        return members.saveAndFlush(m);
    }
    @Test void deduplicatesPerTargetDayAndExcludesOwnerViews() {
        assertThat(insights.record(Kind.POST,post.getId(),"browser",owner.getId())).isFalse();
        insights.record(Kind.POST,post.getId(),"browser",null);
        insights.record(Kind.POST,post.getId(),"browser",null);
        insights.record(Kind.PROJECT,project.getId(),"browser",null);
        insights.record(Kind.POST,post.getId(),"another",null);
        var stats=insights.read(owner.getId(),30);
        assertThat(stats.postViews()).isEqualTo(2);
        assertThat(stats.projectViews()).isEqualTo(1);
        assertThat(stats.posts()).singleElement().satisfies(i->{assertThat(i.id()).isEqualTo(post.getId().toString());assertThat(i.urlKey()).isEqualTo("post");assertThat(i.views()).isEqualTo(2);});
        assertThat(stats.daily()).hasSize(30);
        assertThat(stats.daily().getLast().postViews()).isEqualTo(2);
        assertThat(stats.daily().getFirst().postViews()).isZero();
        assertThat(insights.read(member().getId(),0).postViews()).isZero();
    }
    @Test void periodsUseSeoulDaysAndLifetimeSurvivesRetentionCleanup() {
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"same",today.minusDays(90));
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"same",today.minusDays(30));
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"same",today.minusDays(6));
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"same",today);
        assertThat(insights.read(owner.getId(),7).postViews()).isEqualTo(2);
        assertThat(insights.read(owner.getId(),30).postViews()).isEqualTo(2);
        assertThat(insights.read(owner.getId(),90).postViews()).isEqualTo(3);
        assertThat(insights.read(owner.getId(),0).postViews()).isEqualTo(4);
        insights.cleanupExpired();
        assertThat(jdbc.queryForObject("select count(*) from content_view_visitor where target_id=?",Long.class,post.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from content_view_day where target_id=?",Long.class,post.getId())).isEqualTo(3);
        var all=insights.read(owner.getId(),0);
        assertThat(all.postViews()).isEqualTo(4);assertThat(all.fromDate()).isNull();assertThat(all.chartDays()).isEqualTo(30);
        assertThat(all.trackingStartedAt()).isNotNull();assertThat(all.timeZone()).isEqualTo("Asia/Seoul");
    }
    @Test void cannotCountHiddenContent() {
        post.changeVisibility(PostVisibility.HIDDEN);posts.flush();
        assertThatThrownBy(()->insights.record(Kind.POST,post.getId(),"v",null)).hasMessageContaining("찾을");
    }
    @Test void hiddenAndBlockedHistoryRemainPrivateButDeletedContentDisappears() {
        insights.record(Kind.POST,post.getId(),"v",null);
        insights.record(Kind.PROJECT,project.getId(),"v",null);
        post.changeVisibility(PostVisibility.HIDDEN);posts.flush();
        jdbc.update("update project set is_blocked=true,blocked_at=now(),blocked_by_admin_id=1 where id=?",project.getId());
        var hidden=insights.read(owner.getId(),30);
        assertThat(hidden.posts().getFirst().visibilityStatus()).isEqualTo("HIDDEN");
        assertThat(hidden.projects().getFirst().isBlocked()).isTrue();
        post.delete();posts.flush();
        assertThat(jdbc.queryForObject("select count(*) from content_view_stats where target_id=?",Long.class,post.getId())).isZero();
        assertThat(insights.read(owner.getId(),0).postViews()).isZero();
        assertThat(insights.read(owner.getId(),30).daily()).allSatisfy(d->assertThat(d.postViews()).isZero());
        jdbc.update("delete from post where id=?",post.getId());
        assertThat(jdbc.queryForObject("select count(*) from content_view_stats where target_id=?",Long.class,post.getId())).isZero();
    }
    @Test void rankingHasStableTopTenAndSummariesIncludeAllContent() {
        for(int n=2;n<=13;n++) {
            var p=posts.saveAndFlush(new Post(owner,null,"Post "+n,null,PostVisibility.PUBLIC,null,n));
            insights.record(Kind.POST,p.getId(),"v",null);
        }
        var result=insights.read(owner.getId(),30);
        assertThat(result.postViews()).isEqualTo(12);assertThat(result.posts()).hasSize(10);
        assertThat(result.posts().stream().map(i->Long.parseLong(i.id())).toList()).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(result.posts().getFirst().urlKey()).isEqualTo("13");
    }
    @Test void insightReadRequiresActiveCompletedProfile() {
        jdbc.update("update member set status='SUSPENDED' where id=?",owner.getId());
        // Clear the persistence context to emulate a new authenticated HTTP request.
        entityManager.clear();
        assertThatThrownBy(()->insights.read(owner.getId(),30)).hasMessageContaining("정지");
    }
    @Autowired jakarta.persistence.EntityManager entityManager;
}
