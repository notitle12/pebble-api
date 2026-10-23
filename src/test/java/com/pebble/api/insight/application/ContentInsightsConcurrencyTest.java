package com.pebble.api.insight.application;

import static org.assertj.core.api.Assertions.*;
import com.pebble.api.insight.application.ContentInsightsService.Kind;
import com.pebble.api.member.domain.*;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.domain.*;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ContentInsightsConcurrencyTest extends AuthenticationTestSupport {
    @Autowired ContentInsightsService insights;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired JdbcTemplate jdbc;
    @Test void concurrentDuplicatesCountOnceAndDistinctVisitorsNeverLoseIncrements() throws Exception {
        String key=UUID.randomUUID().toString().substring(0,12);
        var owner=agreedMember(new Member("q-"+key,null,MemberStatus.ACTIVE,null,null));
        owner.completeProfile("Blog "+key,"q-"+key,owner.getNickname(),Instant.now());
        members.saveAndFlush(owner);
        var post=posts.saveAndFlush(new Post(owner,null,"Concurrent post",null,PostVisibility.PUBLIC,null,1));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<java.util.concurrent.Callable<Void>>();
            for(int i=0;i<12;i++) tasks.add(()->{insights.record(Kind.POST,post.getId(),"one",null);return null;});
            for(var future:pool.invokeAll(tasks)) future.get();
            assertThat(insights.read(owner.getId(),30).postViews()).isEqualTo(1);
            tasks.clear();
            for(int i=0;i<12;i++) {String visitor="distinct-"+i;tasks.add(()->{insights.record(Kind.POST,post.getId(),visitor,null);return null;});}
            for(var future:pool.invokeAll(tasks)) future.get();
            assertThat(insights.read(owner.getId(),30).postViews()).isEqualTo(13);
            assertThat(insights.read(owner.getId(),30).daily().getLast().postViews()).isEqualTo(13);
        } finally {
            pool.shutdownNow();
            jdbc.update("delete from post where id=?",post.getId());
            members.deleteById(owner.getId());
        }
    }
}
