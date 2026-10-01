package com.pebble.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static RSAPrivateKey privateKey;
    private static JwtDecoder decoder;

    @BeforeAll
    static void configureKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        JwtConfiguration configuration = new JwtConfiguration();
        JwtConfiguration.JwtKeyPair keys = new JwtConfiguration.JwtKeyPair(
                (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic(), "test");
        privateKey = keys.privateKey();
        decoder = configuration.jwtDecoder(keys,
                new JwtProperties("https://pebble.local", "pebble-api", "test", null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsCompleteMemberAccessToken() {
        assertThat(decoder.decode(encode(validClaims())).getSubject()).isEqualTo("member:123");
    }

    @ParameterizedTest
    @MethodSource("invalidClaims")
    void rejectsInvalidClaimsEvenWithTrustedSignature(Map<String, Object> claims) {
        String token = encode(claims);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsModifiedSignature() {
        String token = encode(validClaims());
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String modified = token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
        assertThatThrownBy(() -> decoder.decode(modified)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsOtherRsaAlgorithmsEvenWithTrustedKey() {
        for (JWSAlgorithm algorithm : List.of(JWSAlgorithm.RS384, JWSAlgorithm.RS512)) {
            assertThatThrownBy(() -> decoder.decode(encode(validClaims(), algorithm))).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsUnsignedToken() {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        validClaims().forEach((key, value) -> builder.claim(key,
                value instanceof Instant instant ? Date.from(instant) : value));
        assertThatThrownBy(() -> decoder.decode(new PlainJWT(builder.build()).serialize())).isInstanceOf(JwtException.class);
    }

    private static Stream<Map<String, Object>> invalidClaims() {
        Stream<Map<String, Object>> missing = validClaims().keySet().stream().map(key -> with(key, null));
        Stream<Map<String, Object>> invalid = Stream.of(
                with("iss", "https://untrusted.local"), with("aud", List.of("other-api")),
                with("role", "MASTER"), with("role", 42), with("token_type", "refresh"),
                with("jti", " "), with("jti", 42), with("sub", 42),
                with("sub", "member:"), with("sub", "member:0"), with("sub", "member:-1"),
                with("sub", "member:01"), with("sub", "member:abc"), with("sub", "admin:123"),
                with("sub", "member:9223372036854775808"),
                with("exp", NOW.minusSeconds(120)), with("nbf", NOW.plusSeconds(120)),
                with("iat", NOW.plusSeconds(120)), with("exp", NOW.plusSeconds(901)),
                with("exp", NOW), with("nbf", NOW.plusSeconds(900)));
        return Stream.concat(missing, invalid);
    }

    private static Map<String, Object> validClaims() {
        return new HashMap<>(Map.of("iss", "https://pebble.local", "aud", List.of("pebble-api"),
                "sub", "member:123", "iat", NOW, "nbf", NOW, "exp", NOW.plusSeconds(900),
                "jti", "test-token-id", "role", "USER", "token_type", "access"));
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> claims = validClaims();
        if (value == null) {
            claims.remove(key);
        } else {
            claims.put(key, value);
        }
        return claims;
    }

    private static String encode(Map<String, Object> claims) {
        return encode(claims, JWSAlgorithm.RS256);
    }

    private static String encode(Map<String, Object> claims, JWSAlgorithm algorithm) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        claims.forEach((key, value) -> builder.claim(key, value instanceof Instant instant ? Date.from(instant) : value));
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm)
                .type(JOSEObjectType.JWT).keyID("test").build(), builder.build());
        try {
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
