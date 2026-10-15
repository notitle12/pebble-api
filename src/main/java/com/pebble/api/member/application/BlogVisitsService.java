package com.pebble.api.member.application;

import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class BlogVisitsService {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final MemberQueryService members;
    public record Stats(long totalVisitors,long todayVisitors,String date,String timeZone) { }
    public Stats read(String handle){return stats(members.findPublicBlog(handle).getId(),LocalDate.now(SEOUL));}
    private Stats stats(long memberId,LocalDate day){
        var results=jdbc.query("select total_visitors,case when visit_date=? then today_visitors else 0 end from blog_visit_stats where member_id=?",(r,n)->new Stats(r.getLong(1),r.getLong(2),day.toString(),SEOUL.getId()),day,memberId);
        return results.isEmpty()?new Stats(0,0,day.toString(),SEOUL.getId()):results.getFirst();
    }
}
