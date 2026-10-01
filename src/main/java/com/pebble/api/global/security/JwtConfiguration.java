package com.pebble.api.global.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtKeyPair jwtKeyPair(JwtProperties properties, ResourceLoader resourceLoader) {
        try {
            RSAPrivateKey privateKey = readPrivateKey(resourceLoader.getResource(properties.privateKeyLocation()));
            RSAPublicKey publicKey = readPublicKey(resourceLoader.getResource(properties.publicKeyLocation()));
            if (publicKey.getModulus().bitLength() < 2048 || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalStateException("JWT requires a matching RSA key pair of at least 2048 bits");
            }
            return new JwtKeyPair(privateKey, publicKey, properties.keyId());
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("JWT signing keys could not be loaded", exception);
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
    JwtDecoder jwtDecoder(JwtKeyPair keyPair, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyPair.publicKey()).build();
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> audienceValidator = jwt ->
                jwt.getAudience() != null && jwt.getAudience().contains(properties.audience())
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error(
                                "invalid_token", "JWT audience is not accepted", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.issuer()),
                new JwtTimestampValidator(),
                audienceValidator,
                new JwtClaimValidator<String>("token_type", "access"::equals),
                new JwtClaimValidator<String>("role", "USER"::equals),
                new JwtClaimValidator<String>("sub", subject -> subject != null && subject.startsWith("member:"))));
        return decoder;
    }

    private RSAPrivateKey readPrivateKey(Resource resource) throws IOException, GeneralSecurityException {
        String pem = readPem(resource);
        byte[] der = decodePem(pem, "PRIVATE KEY");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private RSAPublicKey readPublicKey(Resource resource) throws IOException, GeneralSecurityException {
        String pem = readPem(resource);
        byte[] der = decodePem(pem, "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private byte[] decodePem(String pem, String type) {
        String content = pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(content);
    }

    private String readPem(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    record JwtKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey, String keyId) {
    }
}
