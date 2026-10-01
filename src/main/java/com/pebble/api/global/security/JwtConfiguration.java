package com.pebble.api.global.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtKeyPair jwtKeyPair(JwtProperties properties) {
        try {
            byte[] privateDer = keyBytes(properties.privateKeyBase64());
            byte[] publicDer = keyBytes(properties.publicKeyBase64());
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(privateDer));
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(publicDer));
            if (publicKey.getModulus().bitLength() < 2048 || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalStateException("JWT requires a matching RSA key pair of at least 2048 bits");
            }
            return new JwtKeyPair(privateKey, publicKey, properties.keyId());
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            // 키 값이나 디코딩 오류의 원문이 로그에 노출되지 않도록 원인 예외를 전달하지 않는다.
            throw new IllegalStateException("JWT signing keys could not be loaded");
        }
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeyPair keyPair) {
        RSAKey signingKey = new RSAKey.Builder(keyPair.publicKey())
                .privateKey(keyPair.privateKey())
                .keyID(keyPair.keyId())
                .build();
        return new NimbusJwtEncoder((selector, context) -> selector.select(new JWKSet(signingKey)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeyPair keyPair, JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyPair.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        // 식별자를 문자열로 강제 변환하지 않고 원래 타입을 검증한다.
        decoder.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(Map.of(
                "jti", value -> value, "sub", value -> value)));
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.issuer()),
                timestamps,
                jwt -> validateMemberAccessToken(jwt, properties, clock)));
        return decoder;
    }

    private OAuth2TokenValidatorResult validateMemberAccessToken(Jwt jwt, JwtProperties properties, Clock clock) {
        Instant issuedAt = jwt.getIssuedAt();
        Instant notBefore = jwt.getNotBefore();
        Instant expiresAt = jwt.getExpiresAt();
        Object tokenId = jwt.getClaims().get("jti");
        if (issuedAt == null || notBefore == null || expiresAt == null
                || !(tokenId instanceof String id) || id.isBlank()
                || !isMemberSubject(jwt.getClaims().get("sub"))
                || !"USER".equals(jwt.getClaims().get("role"))
                || !"access".equals(jwt.getClaims().get("token_type"))
                || jwt.getAudience() == null || !jwt.getAudience().contains(properties.audience())
                || !expiresAt.isAfter(issuedAt) || !expiresAt.isAfter(notBefore)
                || Duration.between(issuedAt, expiresAt).compareTo(Duration.ofSeconds(900)) > 0
                || issuedAt.isAfter(clock.instant().plusSeconds(60))) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "JWT claims are not accepted", null));
        }
        return OAuth2TokenValidatorResult.success();
    }

    private boolean isMemberSubject(Object claim) {
        if (!(claim instanceof String subject) || !subject.matches("member:[1-9][0-9]{0,18}")) {
            return false;
        }
        try {
            return Long.parseLong(subject.substring("member:".length())) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private byte[] keyBytes(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("JWT RSA Base64 keys are required");
        }
        return Base64.getDecoder().decode(base64);
    }

    record JwtKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey, String keyId) {
    }
}
