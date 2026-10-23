package com.pebble.api.insight.application;

import static org.assertj.core.api.Assertions.*;
import com.pebble.api.insight.application.ContentInsightsService.*;
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
class ContentInsightDetailsTest extends AuthenticationTestSupport {
    @Autowired ContentInsightsService insights;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired ProjectRepository projects;
    @Autowired JdbcTemplate jdbc;
    Member owner;
    Post post;
    LocalDate today=LocalDate.now(ContentInsightsService.SEOUL);
    @BeforeEach void setup() {
        owner=member();post=posts.saveAndFlush(new Post(owner,null,"Detailed post",null,PostVisibility.PUBLIC,null,1));
    }
    Member member() {
        String key=UUID.randomUUID().toString().substring(0,12);
        var m=agreedMember(new Member("d-"+key,null,MemberStatus.ACTIVE,null,null));
        m.completeProfile("Blog "+key,"d-"+key,m.getNickname(),Instant.now());
        return members.saveAndFlush(m);
    }
    @Test void firstAcceptedDailyViewKeepsItsSourceAndUpdatesEveryAggregateOnce() {
        insights.record(Kind.POST,post.getId(),"v",null,Source.GOOGLE);
        insights.record(Kind.POST,post.getId(),"v",null,Source.NAVER);
        insights.record(Kind.POST,post.getId(),"w",null,Source.NAVER);
        insights.record(Kind.POST,post.getId(),"self",owner.getId(),Source.DIRECT);
        var detail=insights.detail(owner.getId(),Kind.POST,post.getId(),30);
        assertThat(detail.item().views()).isEqualTo(2);assertThat(detail.totalViews()).isEqualTo(2);
        assertThat(detail.daily()).hasSize(30);assertThat(detail.daily().getLast().views()).isEqualTo(2);
        assertThat(detail.monthly()).hasSize(12);assertThat(detail.monthly().getLast().views()).isEqualTo(2);
        assertThat(detail.sources()).containsExactly(new Referrer(Source.GOOGLE,1),new Referrer(Source.NAVER,1));
        assertThat(detail.kind()).isEqualTo("posts");assertThat(detail.sourceTrackingStartedAt()).isNotNull();
        assertThat(insights.detail(owner.getId(),Kind.POST,post.getId(),0).chartDays()).isEqualTo(90);
    }
    @Test void sourceWindowIsSevenDaysRegardlessOfSelectedViewPeriod() {
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"v",today.minusDays(31),Source.GITHUB);
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"v",today.minusDays(7),Source.NAVER);
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"v",today,Source.INTERNAL);
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"v",today.minusDays(6),Source.GOOGLE);
        assertThat(insights.detail(owner.getId(),Kind.POST,post.getId(),7).sources()).containsExactly(new Referrer(Source.GOOGLE,1),new Referrer(Source.INTERNAL,1));
        assertThat(insights.detail(owner.getId(),Kind.POST,post.getId(),30).item().views()).isEqualTo(3);
        jdbc.update("update content_view_stats set views=views+5 where kind='POST' and target_id=?",post.getId());
        var all=insights.detail(owner.getId(),Kind.POST,post.getId(),0);
        assertThat(all.totalViews()).isEqualTo(9);
        assertThat(all.sources()).containsExactly(new Referrer(Source.GOOGLE,1),new Referrer(Source.INTERNAL,1));
        assertThat(all.sourceDays()).isEqualTo(7);assertThat(all.sourceFromDate()).isEqualTo(today.minusDays(6).toString());
        insights.cleanupExpired();
        assertThat(jdbc.queryForObject("select count(*) from content_view_source_day where target_id=?",Long.class,post.getId())).isEqualTo(2);
        assertThat(insights.detail(owner.getId(),Kind.POST,post.getId(),90).sources()).isEqualTo(all.sources());
    }
    @Test void monthToYearCompactionIsExactRepeatableAndDoesNotRetainOldSources() {
        LocalDate firstRetained=today.withDayOfMonth(1).minusMonths(23);
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"v",firstRetained.minusMonths(1),Source.GOOGLE);
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"v",firstRetained.minusMonths(2),Source.GOOGLE);
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"v",firstRetained,Source.NAVER);
        insights.recordDay(Kind.POST,post.getId(),owner.getId(),"v",today,Source.DIRECT);
        var before=insights.detail(owner.getId(),Kind.POST,post.getId(),0);
        insights.cleanupExpired();insights.cleanupExpired();
        var after=insights.detail(owner.getId(),Kind.POST,post.getId(),0);
        assertThat(after.sources()).isEqualTo(before.sources());assertThat(after.totalViews()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select sum(views) from content_view_year where target_id=?",Long.class,post.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from content_view_month where target_id=?",Long.class,post.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from content_view_source_day where target_id=?",Long.class,post.getId())).isEqualTo(1);
        assertThat(after.monthly()).isEqualTo(before.monthly());
        post.delete();posts.flush();
        for(String table:List.of("content_view_year","content_view_month","content_view_source_day"))
            assertThat(jdbc.queryForObject("select count(*) from "+table+" where target_id=?",Long.class,post.getId())).isZero();
    }
    @Test void listFindsZeroViewDraftsAndIsIsolatedAcrossOwnersAndKinds() {
        for(int n=2;n<=12;n++) posts.saveAndFlush(new Post(owner,null,"Article "+n,null,PostVisibility.HIDDEN,null,n));
        var other=posts.saveAndFlush(new Post(member(),null,"Other owner",null,PostVisibility.PUBLIC,null,1));
        var project=projects.saveAndFlush(new Project(owner,"Detailed project",null,null,null,null,ProjectLifecycleStatus.IN_PROGRESS,null,null,ProjectVisibility.HIDDEN));
        var first=insights.list(owner.getId(),Kind.POST,0,"");
        assertThat(first.totalElements()).isEqualTo(12);assertThat(first.totalPages()).isEqualTo(2);assertThat(first.items()).hasSize(10);
        assertThat(first.items()).allSatisfy(i->assertThat(i.views()).isZero());
        assertThat(insights.list(owner.getId(),Kind.POST,1,"").items()).hasSize(2);
        assertThat(insights.list(owner.getId(),Kind.POST,0,"detailed").items()).singleElement().satisfies(i->assertThat(i.id()).isEqualTo(post.getId().toString()));
        assertThat(insights.list(owner.getId(),Kind.POST,0,"%_").items()).isEmpty();
        assertThat(insights.detail(owner.getId(),Kind.PROJECT,project.getId(),30).item().views()).isZero();
        assertThatThrownBy(()->insights.detail(owner.getId(),Kind.POST,other.getId(),30)).hasMessageContaining("찾을");
    }
    @Test void cleanupLimitsEachBatchRatherThanDeletingAllExpiredRowsAtOnce() {
        insights.record(Kind.POST,post.getId(),"current",null);
        jdbc.update("insert into content_view_visitor(kind,target_id,view_date,visitor_hash) select 'POST',?,?::date,md5(n::text)||md5(n::text) from generate_series(1,5001)n",post.getId(),today.minusDays(1));
        insights.cleanupExpired();
        assertThat(jdbc.queryForObject("select count(*) from content_view_visitor where target_id=? and view_date<?",Long.class,post.getId(),today)).isEqualTo(1);
        insights.cleanupExpired();
        assertThat(jdbc.queryForObject("select count(*) from content_view_visitor where target_id=? and view_date<?",Long.class,post.getId(),today)).isZero();
    }
}
