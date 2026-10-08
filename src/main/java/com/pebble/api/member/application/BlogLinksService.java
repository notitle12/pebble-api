package com.pebble.api.member.application;

import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.media.ImageProcessor;
import com.pebble.api.global.media.MediaDeletionQueue;
import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.global.media.R2ObjectStorage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class BlogLinksService {
    private final JdbcTemplate jdbc;
    private final MemberQueryService members;
    private final ImageProcessor images;
    private final ObjectProvider<R2ObjectStorage> storage;
    private final MediaDeletionQueue deletion;
    public record SiteInput(Long id,String label,String url) { }
    public record Input(String githubUrl,List<SiteInput> sites) { }
    public record Site(String id,String label,String url,String logoUrl) { }
    public record Links(String githubUrl,List<Site> sites) { }
    private record Row(long id,String kind,String label,String url,String key,int order) { }
    private List<Row> rows(long memberId){return jdbc.query("select id,kind,label,url,logo_storage_key,display_order from blog_link where member_id=? order by display_order",(r,n)->new Row(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6)),memberId);}
    public Links publicLinks(String handle){return response(rows(members.findPublicBlog(handle).getId()));}
    public Links mine(long memberId){members.findProfileCompleted(memberId);return response(rows(memberId));}
    private Links response(List<Row> rows){var client=storage.getIfAvailable();return new Links(rows.stream().filter(r->r.kind.equals("GITHUB")).map(Row::url).findFirst().orElse(null),rows.stream().filter(r->r.kind.equals("SITE")).map(r->new Site(Long.toString(r.id),r.label,r.url,client==null?null:client.signedUrl(r.key))).toList());}

    @Transactional
    public Links save(long memberId,Input input,Map<Integer,byte[]> logos){
        members.findProfileCompletedForWrite(memberId);
        List<Row> old=rows(memberId);
        // 업로드 전 전체 입력과 기존 ID 소유권을 확인한다. 로고 키는 클라이언트 입력을 받지 않는다.
        for(int index:logos.keySet())if(index<0||index>=input.sites().size())throw MediaRequest.invalid();
        for(int i=0;i<input.sites().size();i++){
            var site=input.sites().get(i);
            if(site.id()!=null&&old.stream().noneMatch(r->r.id==site.id()&&r.kind.equals("SITE")))throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
            if(site.id()==null&&!logos.containsKey(i))throw MediaRequest.invalid();
        }
        var client=storage.getIfAvailable();
        if(!logos.isEmpty()&&client==null)throw new ApplicationException(GlobalErrorCode.STORAGE_UNAVAILABLE);
        List<Row> replacement=new java.util.ArrayList<>();
        if(input.githubUrl()!=null)replacement.add(new Row(TsidGenerator.generate(),"GITHUB","GitHub",input.githubUrl(),null,0));
        for(int i=0;i<input.sites().size();i++){
            var site=input.sites().get(i);String key=site.id()==null?null:old.stream().filter(r->r.id==site.id()).findFirst().orElseThrow().key;
            if(logos.containsKey(i)){
                byte[] logo=logos.get(i);if(logo.length==0||logo.length>2*1024*1024)throw new ApplicationException(GlobalErrorCode.MEDIA_TOO_LARGE);
                var processed=images.process(logo);key="member/"+UUID.randomUUID()+"/blog-logo.webp";
                var keys=List.of(key);deletion.stage(keys);deletion.lockStaged(keys);client.put(key,processed.thumbnail());deletion.retain(keys);
            }
            replacement.add(new Row(site.id()==null?TsidGenerator.generate():site.id(),"SITE",site.label(),site.url(),key,i+1));
        }
        jdbc.update("delete from blog_link where member_id=?",memberId);
        for(Row row:replacement){jdbc.update("insert into blog_link(id,member_id,kind,label,url,logo_storage_key,display_order) values (?,?,?,?,?,?,?)",row.id,memberId,row.kind,row.label,row.url,row.key,row.order);}
        // 유지한 키는 DELETE 트리거의 큐에서 제거한다. 롤백 시 기존 참조는 회수 작업에서도 확인한다.
        for(Row row:replacement)if(row.key!=null)deletion.retain(List.of(row.key));
        return response(replacement);
    }
}
