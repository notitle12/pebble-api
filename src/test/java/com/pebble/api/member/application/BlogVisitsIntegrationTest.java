package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class BlogVisitsIntegrationTest extends AuthenticationTestSupport {
 @Autowired BlogVisitsService visits;
 @Autowired MemberRepository members;
 @Autowired JdbcTemplate jdbc;
 @Test void retiredVisitsReadPreservesHistoricalAggregateWithoutCreatingIdentifiers(){
  String key=UUID.randomUUID().toString().substring(0,12);var member=agreedMember(new Member("visits-"+key,null,MemberStatus.ACTIVE,null,null));
  member.completeProfile("방문-"+key,"visits-"+key,member.getNickname(),Instant.now());members.saveAndFlush(member);
  jdbc.update("insert into blog_visit_stats(member_id,total_visitors,visit_date,today_visitors) values (?,10,current_date-1,2)",member.getId());
  assertThat(visits.read(member.getHandle()).totalVisitors()).isEqualTo(10);
  assertThat(visits.read(member.getHandle()).todayVisitors()).isZero();
  assertThat(jdbc.queryForObject("select count(*) from blog_visit_daily where member_id=?",Long.class,member.getId())).isZero();
 }
}
