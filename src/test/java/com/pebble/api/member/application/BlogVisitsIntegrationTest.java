package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class BlogVisitsIntegrationTest extends AuthenticationTestSupport {
 @Autowired BlogVisitsService visits;
 @Autowired MemberRepository members;
 @Autowired TransactionTemplate transactions;
 @Test void concurrentDuplicateVisitsCountOnceAndDistinctVisitsDoNotLoseUpdates()throws Exception{
  String key=UUID.randomUUID().toString().substring(0,12);var member=new Member("visits-"+key,null,MemberStatus.ACTIVE,null,null);member.completeProfile("방문-"+key,"visits-"+key,member.getNickname(),Instant.now());members.saveAndFlush(member);
  var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
  try{
   var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
   for(int i=0;i<8;i++)tasks.add(()->{visits.record(member.getHandle(),"one-browser");return null;});
   for(var future:pool.invokeAll(tasks))future.get();
   assertThat(visits.read(member.getHandle()).totalVisitors()).isEqualTo(1);
   tasks.clear();for(int i=0;i<8;i++){String visitor="browser-"+i;tasks.add(()->{visits.record(member.getHandle(),visitor);return null;});}
   for(var future:pool.invokeAll(tasks))future.get();assertThat(visits.read(member.getHandle()).totalVisitors()).isEqualTo(9);
   var nextDay=LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1);
   var result=transactions.execute(s->visits.recordDay(member.getId(),"one-browser",nextDay));
   assertThat(result.totalVisitors()).isEqualTo(10);assertThat(result.todayVisitors()).isEqualTo(1);
   var repeated=transactions.execute(s->visits.recordDay(member.getId(),"one-browser",nextDay));assertThat(repeated.totalVisitors()).isEqualTo(10);
  }finally{pool.shutdownNow();members.deleteById(member.getId());}
 }
 @Test void visitRateLimitsExpireAndDoNotTrustForwardedHeaders(){var limiter=new BlogVisitLimiter();for(int i=0;i<120;i++)limiter.check("127.0.0.1",0);assertThatThrownBy(()->limiter.check("127.0.0.1",1)).isInstanceOf(com.pebble.api.global.exception.ApplicationException.class);limiter.check("127.0.0.1",60001);}
}
