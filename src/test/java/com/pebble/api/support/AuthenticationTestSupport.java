package com.pebble.api.support;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("test")
public abstract class AuthenticationTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private static final String PRIVATE_KEY;
    private static final String PUBLIC_KEY;
    private static final String PEPPER;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            PRIVATE_KEY = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            PUBLIC_KEY = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            byte[] pepper = new byte[32];
            new SecureRandom().nextBytes(pepper);
            PEPPER = Base64.getEncoder().encodeToString(pepper);
        } catch (GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void authenticationProperties(DynamicPropertyRegistry registry) {
        registry.add("pebble.auth.jwt.private-key-base64", () -> PRIVATE_KEY);
        registry.add("pebble.auth.jwt.public-key-base64", () -> PUBLIC_KEY);
        registry.add("pebble.auth.refresh-token.pepper-base64", () -> PEPPER);
        registry.add("pebble.auth.jwt.issuer", () -> "https://pebble.local");
        registry.add("pebble.auth.jwt.audience", () -> "pebble-api");
        registry.add("pebble.auth.jwt.key-id", () -> "test");
        registry.add("pebble.security.cors.allowed-origins", () -> "http://localhost:3000");
        registry.add("pebble.auth.naver.client-id", () -> "test-client-id");
        registry.add("pebble.auth.naver.client-secret", () -> "test-client-secret");
    }

    @BeforeEach
    void seedBlockingAdmin() {
        jdbc.update("""
                insert into admin_account (id, login_id, password_hash, role, status, created_at, updated_at)
                values (1, 'test-blocking-admin', 'argon2placeholderhash', 'MANAGER', 'ACTIVE', now(), now())
                on conflict (id) do nothing
                """);
    }

}
