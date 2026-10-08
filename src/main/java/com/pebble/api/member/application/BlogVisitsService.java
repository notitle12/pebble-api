package com.pebble.api.member.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
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
    @Transactional
    public Stats record(String handle,String visitor){return recordDay(members.findPublicBlog(handle).getId(),visitor,LocalDate.now(SEOUL));}
    Stats recordDay(long memberId,String visitor,LocalDate day){
        String hash=hash(memberId+":"+day+":"+visitor);
        int added=jdbc.update("insert into blog_visit_daily(member_id,visit_date,visitor_hash) values(?,?,?) on conflict do nothing",memberId,day,hash);
        if(added>0)jdbc.update("""
                insert into blog_visit_stats(member_id,total_visitors,visit_date,today_visitors) values(?,1,?,1)
                on conflict(member_id) do update set total_visitors=blog_visit_stats.total_visitors+1,
                today_visitors=case when excluded.visit_date=blog_visit_stats.visit_date then blog_visit_stats.today_visitors+1
                when excluded.visit_date>blog_visit_stats.visit_date then 1 else blog_visit_stats.today_visitors end,
                visit_date=greatest(blog_visit_stats.visit_date,excluded.visit_date)
                """,memberId,day);
        // 총계는 보존하고 지난 중복 식별자만 삭제한다. 원시 쿠키·IP·회원 인증 정보는 저장하지 않는다.
        jdbc.update("delete from blog_visit_daily where member_id=? and visit_date<?",memberId,day.minusDays(1));
        return stats(memberId,day);
    }
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
