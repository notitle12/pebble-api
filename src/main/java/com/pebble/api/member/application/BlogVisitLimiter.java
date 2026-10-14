package com.pebble.api.member.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.domain.MemberError;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 단일 API 인스턴스의 방문 쓰기 남용을 제한한다. 프록시 헤더는 신뢰하지 않는다. */
@Component
public class BlogVisitLimiter {
    private record Bucket(long expires,int count) { }
    private final Map<String,Bucket> buckets=new HashMap<>();
    public void check(String remoteAddress){check(remoteAddress,System.currentTimeMillis());}
    synchronized void check(String remoteAddress,long now){
        String key=BlogVisitsService.hash(remoteAddress);
        Bucket previous=buckets.get(key);
        if(previous==null||previous.expires<=now){
            buckets.entrySet().removeIf(entry->entry.getValue().expires<=now);
            if(buckets.size()>=10000)throw new ApplicationException(MemberError.VISIT_RATE_LIMITED);
            previous=new Bucket(now+60000,0);
        }
        if(previous.count>=120)throw new ApplicationException(MemberError.VISIT_RATE_LIMITED);
        buckets.put(key,new Bucket(previous.expires,previous.count+1));
    }
}
