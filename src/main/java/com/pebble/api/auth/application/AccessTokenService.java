package com.pebble.api.auth.application;

import com.pebble.api.global.security.JwtProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccessTokenService {

    public static final long EXPIRES_IN_SECONDS = 900;

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public String issueForMember(long memberId, Instant issuedAt) {
        return issue("member:" + memberId, "USER", null, issuedAt);
    }

    public String issueForAdmin(long adminId, String role, String sessionId, Instant issuedAt) {
        if (!("MANAGER".equals(role) || "MASTER".equals(role)) || sessionId == null || sessionId.isBlank())
            throw new IllegalArgumentException("Invalid administrator token identity");
        return issue("admin:" + adminId, role, sessionId, issuedAt);
    }

    private String issue(String subject, String role, String sessionId, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(Duration.ofSeconds(EXPIRES_IN_SECONDS));
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(subject)
                .audience(List.of(properties.audience()))
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("role", role)
                .claim("token_type", "access");
        if (sessionId != null) builder.claim("sid", sessionId);
        JwtClaimsSet claims = builder.build();
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.keyId()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }
}
