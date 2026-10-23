package com.pebble.api.insight.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.BlogVisitsService;
import com.pebble.api.member.domain.MemberError;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class ContentInsightsService {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final MemberRepository members;

    public enum Kind {
        POST("post","author_member_id"), PROJECT("project","owner_member_id");
        private final String table;
        private final String owner;
        Kind(String table,String owner) { this.table=table; this.owner=owner; }
    }
    public record Item(String id,String title,String urlKey,long views,String visibilityStatus,boolean isBlocked,boolean draft) { }
    public record Day(String date,long postViews,long projectViews) { }
    public record Insights(int days,String fromDate,String toDate,String timeZone,Instant trackingStartedAt,int chartDays,
                           long postViews,long projectViews,List<Day> daily,List<Item> posts,List<Item> projects) { }

    /** Locks member before content, matching withdrawal and content mutation lock order. */
    @Transactional
    public boolean record(Kind kind,long targetId,String visitor,Long signedMemberId) {
        var owners=jdbc.query("select "+kind.owner+" from "+kind.table+" where id=?",(r,n)->r.getLong(1),targetId);
        if(owners.isEmpty()) throw notFound();
        long owner=owners.getFirst();
        var member=members.findForAuthentication(owner).orElseThrow(ContentInsightsService::notFound);
        if(member.getStatus()==MemberStatus.WITHDRAWAL_PENDING) throw notFound();
        var targets=jdbc.query("select id from "+kind.table+" where id=? and "+kind.owner+"=? and visibility_status='PUBLIC' and deleted_at is null and is_blocked=false"
            +(kind==Kind.POST?" and is_draft=false":"")+" for share",(r,n)->r.getLong(1),targetId,owner);
        if(targets.isEmpty()) throw notFound();
        if(signedMemberId!=null && signedMemberId==owner) return false;
        recordDay(kind,targetId,owner,visitor,LocalDate.now(clock.withZone(SEOUL)));
        return true;
    }

    void recordDay(Kind kind,long targetId,long owner,String visitor,LocalDate day) {
        jdbc.update("insert into content_view_stats(kind,target_id,post_id,project_id,owner_member_id) values(?,?,?,?,?) on conflict do nothing",
            kind.name(),targetId,kind==Kind.POST?targetId:null,kind==Kind.PROJECT?targetId:null,owner);
        int added=jdbc.update("insert into content_view_visitor(kind,target_id,view_date,visitor_hash) values(?,?,?,?) on conflict do nothing",
            kind.name(),targetId,day,BlogVisitsService.hash(kind+":"+targetId+":"+day+":"+visitor));
        if(added==0) return;
        jdbc.update("update content_view_stats set views=views+1 where kind=? and target_id=?",kind.name(),targetId);
        jdbc.update("insert into content_view_day(kind,target_id,view_date,views) values(?,?,?,1) on conflict(kind,target_id,view_date) do update set views=content_view_day.views+1",
            kind.name(),targetId,day);
    }

    @Transactional
    public Insights read(long owner,int days) {
        if(!Set.of(0,7,30,90).contains(days)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        var member=members.findForAuthentication(owner).orElseThrow(ContentInsightsService::notFound);
        if(member.getStatus()==MemberStatus.SUSPENDED) throw new ApplicationException(MemberError.ACCOUNT_SUSPENDED);
        if(member.getStatus()==MemberStatus.WITHDRAWAL_PENDING) throw new ApplicationException(MemberError.ACCOUNT_WITHDRAWAL_PENDING);
        if(!member.isProfileCompleted()) throw new ApplicationException(MemberError.PROFILE_REQUIRED);
        LocalDate today=LocalDate.now(clock.withZone(SEOUL));
        int chartDays=days==0?30:days;
        LocalDate chartFrom=today.minusDays(chartDays-1);
        LocalDate from=days==0?null:today.minusDays(days-1);
        List<Item> posts=ranking(Kind.POST,owner,from,today);
        List<Item> projects=ranking(Kind.PROJECT,owner,from,today);
        Map<LocalDate,long[]> daily=new LinkedHashMap<>();
        for(int i=0;i<chartDays;i++) daily.put(chartFrom.plusDays(i),new long[2]);
        for(Kind kind:Kind.values()) {
            jdbc.query("select d.view_date,sum(d.views) from content_view_day d join content_view_stats s using(kind,target_id) "+liveJoin(kind)
                +" where s.owner_member_id=? and s.kind=? and d.view_date between ? and ? group by d.view_date",
                r->{daily.get(r.getDate(1).toLocalDate())[kind==Kind.POST?0:1]=r.getLong(2);},owner,kind.name(),chartFrom,today);
        }
        var series=daily.entrySet().stream().map(e->new Day(e.getKey().toString(),e.getValue()[0],e.getValue()[1])).toList();
        Instant started=jdbc.queryForObject("select started_at from content_view_started where singleton=true",(r,n)->r.getTimestamp(1).toInstant());
        return new Insights(days,from==null?null:from.toString(),today.toString(),SEOUL.getId(),started,chartDays,
            total(Kind.POST,owner,from,today),total(Kind.PROJECT,owner,from,today),series,posts,projects);
    }

    private String liveJoin(Kind kind) {
        return "join "+kind.table+" c on c.id=s.target_id and c."+kind.owner+"=s.owner_member_id and c.deleted_at is null and c.visibility_status<>'DELETED'";
    }
    private String measure(LocalDate from) { return from==null?"s.views":"coalesce(v.views,0)"; }
    private String periodJoin(LocalDate from) {
        return from==null?"":" left join lateral (select sum(d.views) views from content_view_day d where d.kind=s.kind and d.target_id=s.target_id and d.view_date between ? and ?) v on true ";
    }
    private Object[] args(long owner,Kind kind,LocalDate from,LocalDate today) {
        return from==null?new Object[]{owner,kind.name()}:new Object[]{from,today,owner,kind.name()};
    }
    private long total(Kind kind,long owner,LocalDate from,LocalDate today) {
        return jdbc.queryForObject("select coalesce(sum("+measure(from)+"),0) from content_view_stats s "+liveJoin(kind)+periodJoin(from)
            +" where s.owner_member_id=? and s.kind=?",Long.class,args(owner,kind,from,today));
    }
    private List<Item> ranking(Kind kind,long owner,LocalDate from,LocalDate today) {
        String fields=kind==Kind.POST?"c.title,coalesce(c.slug,c.post_number::text),c.is_draft":"c.name,null::text,false";
        return jdbc.query("select s.target_id,"+fields+","+measure(from)+",c.visibility_status,c.is_blocked from content_view_stats s "+liveJoin(kind)+periodJoin(from)
            +" where s.owner_member_id=? and s.kind=? and "+measure(from)+">0 order by "+measure(from)+" desc,s.target_id desc limit 10",
            (r,n)->new Item(Long.toString(r.getLong(1)),r.getString(2),r.getString(3),r.getLong(5),r.getString(6),r.getBoolean(7),r.getBoolean(4)),args(owner,kind,from,today));
    }
    @Transactional
    public void cleanupExpired() {
        LocalDate today=LocalDate.now(clock.withZone(SEOUL));
        jdbc.update("delete from content_view_visitor where view_date<?",today);
        jdbc.update("delete from content_view_day where view_date<?",today.minusDays(89));
    }
    private static ApplicationException notFound() { return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND); }
}
