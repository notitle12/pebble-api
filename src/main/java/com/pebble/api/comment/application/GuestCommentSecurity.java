package com.pebble.api.comment.application;

import com.pebble.api.comment.domain.CommentError;
import com.pebble.api.global.exception.ApplicationException;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class GuestCommentSecurity {
    private final StringRedisTemplate redis;
    // 관리자 비밀번호 pepper나 계정 자격 증명을 공유하지 않는다. 댓글마다 임의 salt를 사용한다.
    private final Argon2PasswordEncoder encoder=new Argon2PasswordEncoder(16,32,1,19*1024,2);
    private static final DefaultRedisScript<Long> LIMIT=new DefaultRedisScript<>("""
            local allowed = 1
            for i,key in ipairs(KEYS) do
              local n = redis.call('INCR',key)
              if n == 1 then redis.call('PEXPIRE',key,ARGV[4]) end
              if n > tonumber(ARGV[i]) then allowed = 0 end
            end
            return allowed
            """,Long.class);
    public void check(String ip, String target, boolean create) {
        try {
            Long result=redis.execute(LIMIT,List.of("pebble:comment:rate:ip:"+hash(ip),
                    "pebble:comment:rate:target:"+hash(target),"pebble:comment:rate:global"),
                    create?"5":"15",create?"30":"15","120","600000");
            if(!Long.valueOf(1).equals(result))throw new ApplicationException(CommentError.COMMENT_RATE_LIMITED);
        } catch(org.springframework.dao.DataAccessException exception) {
            throw new ApplicationException(CommentError.COMMENT_SECURITY_UNAVAILABLE);
        }
    }
    public String encode(String password){return encoder.encode(password);}
    public boolean matches(String password,String hash){return hash!=null && encoder.matches(password,hash);}
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException exception){throw new IllegalStateException("SHA-256 unavailable");}
    }
}
