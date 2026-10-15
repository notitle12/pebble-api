package com.pebble.api.comment.presentation;

import com.pebble.api.comment.domain.CommentError;
import com.pebble.api.global.exception.ApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

@Component
public class GuestCommentIp {
    private final List<IpAddressMatcher> trustedProxies;
    public GuestCommentIp(@Value("${pebble.comment.trusted-proxy-cidrs:}") String cidrs) {
        trustedProxies=java.util.Arrays.stream(cidrs.split(",")).map(String::strip).filter(s->!s.isEmpty())
                .map(IpAddressMatcher::new).toList();
    }
    public String resolve(HttpServletRequest request) {
        String peer=numeric(request.getRemoteAddr());
        if(trustedProxies.stream().anyMatch(matcher->matcher.matches(peer))) {
            // 신뢰 목록에 있는 자체 프록시가 단일 X-Real-IP를 덮어쓰도록 배포 설정해야 한다.
            var values=Collections.list(request.getHeaders("X-Real-IP"));
            if(values.size()!=1)throw unavailable();
            return numeric(values.getFirst());
        }
        // 임의 X-Forwarded-For/CF-Connecting-IP 값은 기록·차단 기준으로 쓰지 않는다.
        return numeric(peer);
    }
    private String numeric(String value) {
        if(value==null || value.length()>45 || !value.matches("[0-9a-fA-F:.]+"))throw unavailable();
        try {
            // 숫자 리터럴만 허용하여 DNS 조회·포트·헤더 목록을 배제한다.
            if(!value.contains(":") && !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))throw unavailable();
            return java.net.InetAddress.getByName(value).getHostAddress();
        } catch(java.net.UnknownHostException exception){throw unavailable();}
    }
    private ApplicationException unavailable(){return new ApplicationException(CommentError.COMMENT_SECURITY_UNAVAILABLE);}
}
