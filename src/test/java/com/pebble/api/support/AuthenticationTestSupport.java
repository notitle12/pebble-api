package com.pebble.api.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class AuthenticationTestSupport {

    private static final Path PRIVATE_KEY;
    private static final Path PUBLIC_KEY;
    private static final String PEPPER;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            PRIVATE_KEY = pemFile("PRIVATE KEY", pair.getPrivate().getEncoded());
            PUBLIC_KEY = pemFile("PUBLIC KEY", pair.getPublic().getEncoded());
            byte[] pepper = new byte[32];
            new SecureRandom().nextBytes(pepper);
            PEPPER = Base64.getEncoder().encodeToString(pepper);
        } catch (GeneralSecurityException | IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void authenticationProperties(DynamicPropertyRegistry registry) {
        registry.add("pebble.auth.jwt.private-key-location", () -> PRIVATE_KEY.toUri().toString());
        registry.add("pebble.auth.jwt.public-key-location", () -> PUBLIC_KEY.toUri().toString());
        registry.add("pebble.auth.refresh-token.pepper-base64", () -> PEPPER);
        registry.add("pebble.auth.jwt.issuer", () -> "https://pebble.local");
        registry.add("pebble.auth.jwt.audience", () -> "pebble-api");
        registry.add("pebble.auth.jwt.key-id", () -> "test");
        registry.add("pebble.security.cors.allowed-origins", () -> "http://localhost:3000");
        registry.add("pebble.auth.naver.client-id", () -> "test-client-id");
        registry.add("pebble.auth.naver.client-secret", () -> "test-client-secret");
    }

    private static Path pemFile(String type, byte[] bytes) throws IOException {
        Path path = Files.createTempFile("pebble-test-key-", ".pem");
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n");
        path.toFile().deleteOnExit();
        return path;
    }
}
