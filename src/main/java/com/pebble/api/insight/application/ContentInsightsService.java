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
    public enum ListSort { LATEST,VIEWS,POPULAR }
    public enum Direction { ASC,DESC }
    public enum Source { UNKNOWN,DIRECT,INTERNAL,GOOGLE,NAVER,BING,DAUM,GITHUB,KAKAO,OTHER }
    public record Point(String date,long views) { }
    public record Month(String month,long views) { }
    public record Referrer(Source source,long views) { }
    public record ContentList(String kind,int page,int size,long totalElements,long totalPages,List<Item> items,String sort,String direction,Map<String,Long> likeCounts) { }
    public record Detail(String kind,int days,Item item,long totalViews,String fromDate,String toDate,String timeZone,
                         Instant trackingStartedAt,Instant sourceTrackingStartedAt,String monthlyCoverageFrom,int chartDays,
                         List<Point> daily,List<Month> monthly,int sourceDays,String sourceFromDate,List<Referrer> sources) { }
    public record Item(String id,String title,String urlKey,long views,String visibilityStatus,boolean isBlocked,boolean draft) { }
    public record Day(String date,long postViews,long projectViews) { }
    public record Insights(int days,String fromDate,String toDate,String timeZone,Instant trackingStartedAt,int chartDays,
                           long postViews,long projectViews,List<Day> daily,List<Item> posts,List<Item> projects) { }

    /** Locks member before content, matching withdrawal and content mutation lock order. */
    @Transactional
    public boolean record(Kind kind,long targetId,String visitor,Long signedMemberId) {
        return record(kind,targetId,visitor,signedMemberId,Source.UNKNOWN);
    }
    @Transactional
    public boolean record(Kind kind,long targetId,String visitor,Long signedMemberId,Source source) {
        var owners=jdbc.query("select "+kind.owner+" from "+kind.table+" where id=?",(r,n)->r.getLong(1),targetId);
        if(owners.isEmpty()) throw notFound();
        long owner=owners.getFirst();
        var member=members.findForAuthentication(owner).orElseThrow(ContentInsightsService::notFound);
        if(member.getStatus()==MemberStatus.WITHDRAWAL_PENDING) throw notFound();
        var targets=jdbc.query("select id from "+kind.table+" where id=? and "+kind.owner+"=? and visibility_status='PUBLIC' and deleted_at is null and is_blocked=false"
            +(kind==Kind.POST?" and is_draft=false":"")+" for share",(r,n)->r.getLong(1),targetId,owner);
        if(targets.isEmpty()) throw notFound();
        if(signedMemberId!=null && signedMemberId==owner) return false;
        recordDay(kind,targetId,owner,visitor,LocalDate.now(clock.withZone(SEOUL)),source);
        return true;
    }

    void recordDay(Kind kind,long targetId,long owner,String visitor,LocalDate day) {
        recordDay(kind,targetId,owner,visitor,day,Source.UNKNOWN);
    }
    void recordDay(Kind kind,long targetId,long owner,String visitor,LocalDate day,Source source) {
        jdbc.update("insert into content_view_stats(kind,target_id,post_id,project_id,owner_member_id) values(?,?,?,?,?) on conflict do nothing",
            kind.name(),targetId,kind==Kind.POST?targetId:null,kind==Kind.PROJECT?targetId:null,owner);
        int added=jdbc.update("insert into content_view_visitor(kind,target_id,view_date,visitor_hash) values(?,?,?,?) on conflict do nothing",
            kind.name(),targetId,day,BlogVisitsService.hash(kind+":"+targetId+":"+day+":"+visitor));
        if(added==0) return;
        // 집계 쓰기는 잠금 순서를 유지한 한 번의 SQL로 묶어 왕복을 줄인다.
        jdbc.update("""
            with incremented as (
                update content_view_stats set views=views+1 where kind=? and target_id=? returning kind,target_id
            ), daily as (
                insert into content_view_day(kind,target_id,view_date,views)
                select kind,target_id,?::date,1 from incremented
                on conflict(kind,target_id,view_date) do update set views=content_view_day.views+1 returning kind,target_id
            ), sourced as (
                insert into content_view_source_day(kind,target_id,view_date,source,views)
                select kind,target_id,?::date,?,1 from daily
                on conflict(kind,target_id,view_date,source) do update set views=content_view_source_day.views+1 returning kind,target_id
            ) insert into content_view_month(kind,target_id,view_month,views)
            select kind,target_id,?::date,1 from sourced
            on conflict(kind,target_id,view_month) do update set views=content_view_month.views+1
            """,kind.name(),targetId,day,day,source.name(),day.withDayOfMonth(1));
    }

    @Transactional
    public Insights read(long owner,int days) {
        if(!Set.of(0,7,30,90).contains(days)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        authorize(owner);
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


    private void authorize(long owner) {
        var member=members.findForAuthentication(owner).orElseThrow(ContentInsightsService::notFound);
        if(member.getStatus()==MemberStatus.SUSPENDED) throw new ApplicationException(MemberError.ACCOUNT_SUSPENDED);
        if(member.getStatus()==MemberStatus.WITHDRAWAL_PENDING) throw new ApplicationException(MemberError.ACCOUNT_WITHDRAWAL_PENDING);
        if(!member.isProfileCompleted()) throw new ApplicationException(MemberError.PROFILE_REQUIRED);
    }

    @Transactional
    public ContentList list(long owner,Kind kind,int page,String query) {
        return list(owner,kind,page,query,ListSort.LATEST,Direction.DESC);
    }
    @Transactional
    public ContentList list(long owner,Kind kind,int page,String query,ListSort sort,Direction direction) {
        authorize(owner);
        if(page<0||page>10000||query==null||query.codePointCount(0,query.length())>100||query.codePoints().anyMatch(Character::isISOControl)||sort==null||direction==null) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        String fields=kind==Kind.POST?"c.title as insight_title,coalesce(c.slug,c.post_number::text) as insight_url_key,c.is_draft as insight_draft":"c.name as insight_title,null::text as insight_url_key,false as insight_draft";
        String title=kind==Kind.POST?"c.title":"c.name";
        String where=" where c."+kind.owner+"=? and c.deleted_at is null and c.visibility_status<>'DELETED'";
        if(!query.isEmpty()) where+=" and position(lower(?) in lower("+title+"))>0";
        Object[] countArgs=query.isEmpty()?new Object[]{owner}:new Object[]{owner,query};
        // 개수에는 통계·공감 조인을 하지 않는다. 조회 0인 콘텐츠도 작성자 범위에 포함한다.
        long total=jdbc.queryForObject("select count(*) from "+kind.table+" c"+where,Long.class,countArgs);
        String popularity="";
        if(sort==ListSort.POPULAR) {
            String target=kind==Kind.POST?"post_id":"project_id";
            popularity=" left join lateral (select count(*) likes from "+kind.table+"_like l join member voter on voter.id=l.member_id where l."+target+"=c.id and l.deleted_at is null and voter.status<>'WITHDRAWAL_PENDING') pop on true";
        }
        // LIMIT을 유지한 lateral로 본인 콘텐츠의 PK 조회만 하며 전체 통계의 조인 집계를 피한다.
        String stats=" left join lateral (select st.views from content_view_stats st where st.kind=? and st.target_id=c.id limit 1) s on true";
        String metric=sort==ListSort.POPULAR?"pop.likes":"null::bigint";
        String order=switch(sort) {
            case LATEST -> "c.created_at "+direction+",c.id "+direction;
            case VIEWS -> "coalesce(s.views,0) "+direction+",c.id "+direction;
            case POPULAR -> "pop.likes "+direction+",c.created_at "+direction+",c.id "+direction;
        };
        String finalOrder=switch(sort) {
            case LATEST -> "c.created_at "+direction+",c.id "+direction;
            case VIEWS -> "c.insight_views "+direction+",c.id "+direction;
            case POPULAR -> "c.insight_likes "+direction+",c.created_at "+direction+",c.id "+direction;
        };
        String selected="select c.id,"+fields+",c.created_at,c.visibility_status,c.is_blocked,"+metric+" as insight_likes"
            +(sort==ListSort.VIEWS?",coalesce(s.views,0) as insight_views":"")+" from "+kind.table+" c"
            +(sort==ListSort.VIEWS?stats:popularity)+where+" order by "+order+" limit 10 offset ?";
        String sql="with selected as materialized ("+selected+") select c.id,c.insight_title,c.insight_url_key,c.insight_draft,"
            +(sort==ListSort.VIEWS?"c.insight_views":"coalesce(s.views,0)")+",c.visibility_status,c.is_blocked,c.insight_likes from selected c"
            +(sort==ListSort.VIEWS?"":stats)+" order by "+finalOrder;
        var args=new ArrayList<Object>();
        if(sort==ListSort.VIEWS) args.add(kind.name());
        args.addAll(Arrays.asList(countArgs));args.add(page*10);
        if(sort!=ListSort.VIEWS) args.add(kind.name());
        var likes=new LinkedHashMap<String,Long>();
        var items=jdbc.query(sql,(r,n)->{
                String id=Long.toString(r.getLong(1));
                if(sort==ListSort.POPULAR) likes.put(id,r.getLong(8));
                return new Item(id,r.getString(2),r.getString(3),r.getLong(5),r.getString(6),r.getBoolean(7),r.getBoolean(4));
            },args.toArray());
        return new ContentList(route(kind),page,10,total,(total+9)/10,items,sort.name(),direction.name(),likes);
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Detail detail(long owner,Kind kind,long id,int days) {
        authorize(owner);
        if(!Set.of(0,7,30,90).contains(days)) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        String fields=kind==Kind.POST?"c.title,coalesce(c.slug,c.post_number::text),c.is_draft":"c.name,null::text,false";
        var items=jdbc.query("select c.id,"+fields+",c.visibility_status,c.is_blocked from "+kind.table+" c where c.id=? and c."+kind.owner+"=? and c.deleted_at is null and c.visibility_status<>'DELETED' for share",
            (r,n)->new Item(Long.toString(r.getLong(1)),r.getString(2),r.getString(3),0,r.getString(5),r.getBoolean(6),r.getBoolean(4)),id,owner);
        if(items.isEmpty()) throw notFound();
        LocalDate today=LocalDate.now(clock.withZone(SEOUL));
        int chartDays=days==0?90:days;
        LocalDate from=today.minusDays(chartDays-1);
        var daily=new LinkedHashMap<LocalDate,Long>();
        for(int i=0;i<chartDays;i++) daily.put(from.plusDays(i),0L);
        jdbc.query("select view_date,views from content_view_day where kind=? and target_id=? and view_date between ? and ?",
            r->{daily.put(r.getDate(1).toLocalDate(),r.getLong(2));},kind.name(),id,from,today);
        var monthly=new LinkedHashMap<YearMonth,Long>();
        YearMonth month=YearMonth.from(today);
        for(int i=11;i>=0;i--) monthly.put(month.minusMonths(i),0L);
        jdbc.query("select view_month,views from content_view_month where kind=? and target_id=? and view_month between ? and ?",
            r->{monthly.put(YearMonth.from(r.getDate(1).toLocalDate()),r.getLong(2));},kind.name(),id,month.minusMonths(11).atDay(1),month.atDay(1));
        var counters=jdbc.query("select views from content_view_stats where kind=? and target_id=?",(r,n)->r.getLong(1),kind.name(),id);
        long total=counters.isEmpty()?0:counters.getFirst();
        long views=days==0?total:daily.values().stream().mapToLong(Long::longValue).sum();
        var sources=new EnumMap<Source,Long>(Source.class);
        LocalDate sourceFrom=today.minusDays(6);
        jdbc.query("select source,sum(views) from content_view_source_day where kind=? and target_id=? and view_date between ? and ? group by source",
            r->{sources.put(Source.valueOf(r.getString(1)),r.getLong(2));},kind.name(),id,sourceFrom,today);
        var sourceRows=sources.entrySet().stream().filter(e->e.getValue()>0).map(e->new Referrer(e.getKey(),e.getValue()))
            .sorted(Comparator.comparingLong(Referrer::views).reversed().thenComparing(r->r.source().name())).toList();
        var timestamps=jdbc.queryForObject("select s.started_at,d.source_started_at,d.monthly_coverage_from from content_view_started s cross join content_view_detail_started d",
            (r,n)->new Object[]{r.getTimestamp(1).toInstant(),r.getTimestamp(2).toInstant(),r.getDate(3).toLocalDate().toString()});
        Item item=items.getFirst();
        return new Detail(route(kind),days,new Item(item.id(),item.title(),item.urlKey(),views,item.visibilityStatus(),item.isBlocked(),item.draft()),total,days==0?null:from.toString(),today.toString(),SEOUL.getId(),
            (Instant)timestamps[0],(Instant)timestamps[1],(String)timestamps[2],chartDays,
            daily.entrySet().stream().map(e->new Point(e.getKey().toString(),e.getValue())).toList(),
            monthly.entrySet().stream().map(e->new Month(e.getKey().toString(),e.getValue())).toList(),7,sourceFrom.toString(),sourceRows);
    }
    private static String route(Kind kind) { return kind==Kind.POST?"posts":"projects"; }

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
        // 날짜 인덱스와 작은 배치로 잠금·삭제 부하를 제한한다.
        for(String table:List.of("content_view_visitor","content_view_day","content_view_source_day")) {
            LocalDate before=table.equals("content_view_visitor")?today:today.minusDays(table.equals("content_view_source_day")?6:89);
            jdbc.update("delete from "+table+" where ctid in (select ctid from "+table+" where view_date<? order by view_date limit 5000 for update skip locked)",before);
        }
        jdbc.update("""
            with locked as materialized (
                select s.kind,s.target_id from content_view_stats s join (
                    select distinct kind,target_id from (
                        select kind,target_id from content_view_month where view_month<? order by view_month limit 1000
                    ) candidates
                ) c using(kind,target_id) order by s.kind,s.target_id for update of s skip locked
            ), moved as (
                delete from content_view_month where ctid in (
                    select m.ctid from content_view_month m join locked l using(kind,target_id)
                    where m.view_month<? order by m.view_month limit 1000 for update of m skip locked
                ) returning kind,target_id,view_month,views
            ) insert into content_view_year(kind,target_id,view_year,views)
            select kind,target_id,extract(year from view_month)::integer,sum(views) from moved group by kind,target_id,extract(year from view_month)::integer
            on conflict(kind,target_id,view_year) do update set views=content_view_year.views+excluded.views
            """,today.withDayOfMonth(1).minusMonths(23),today.withDayOfMonth(1).minusMonths(23));
    }
    private static ApplicationException notFound() { return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND); }
}
