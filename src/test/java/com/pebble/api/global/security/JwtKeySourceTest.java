package com.pebble.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.auth.application.AccessTokenService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JwtKeySourceTest {

    private static KeyPair pair;
    private final JwtConfiguration configuration = new JwtConfiguration();

    @BeforeAll
    static void generateKeys() throws Exception {
        pair = generate(3072);
    }

    @Test
    void base64DerKeysIssueAndVerifyRealAccessToken() {
        JwtProperties properties = properties(encoded(pair.getPrivate().getEncoded()),
                encoded(pair.getPublic().getEncoded()));
        var keys = configuration.jwtKeyPair(properties);
        String token = new AccessTokenService(configuration.jwtEncoder(keys), properties)
                .issueForMember(123, Instant.now());
        assertThat(configuration.jwtDecoder(keys, properties, Clock.systemUTC()).decode(token).getSubject())
                .isEqualTo("member:123");
        assertThat(properties.toString()).doesNotContain(properties.privateKeyBase64(), properties.publicKeyBase64());
    }

    @Test
    void base64PropertiesBindAndStartSigningBeans() {
        new ApplicationContextRunner().withUserConfiguration(JwtConfiguration.class)
                .withPropertyValues("pebble.auth.jwt.issuer=https://pebble.local",
                        "pebble.auth.jwt.audience=pebble-api", "pebble.auth.jwt.key-id=test",
                        "pebble.auth.jwt.private-key-base64=" + encoded(pair.getPrivate().getEncoded()),
                        "pebble.auth.jwt.public-key-base64=" + encoded(pair.getPublic().getEncoded()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(org.springframework.security.oauth2.jwt.JwtEncoder.class);
                    assertThat(context).hasSingleBean(org.springframework.security.oauth2.jwt.JwtDecoder.class);
                });
    }

    @Test
    void missingPrivateOrPublicKeyIsRejected() {
        assertThatThrownBy(() -> configuration.jwtKeyPair(properties(null, encoded(pair.getPublic().getEncoded()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> configuration.jwtKeyPair(properties(encoded(pair.getPrivate().getEncoded()), null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("required");
    }

    @Test
    void malformedOrWrongKeyFormatsFailWithoutLeakingValues() {
        for (String invalid : new String[]{"not-a-key!", encoded(new byte[]{1, 2, 3}),
                encoded(pair.getPublic().getEncoded()), pem("PRIVATE KEY", pair.getPrivate().getEncoded())}) {
            assertThatThrownBy(() -> configuration.jwtKeyPair(properties(invalid,
                    encoded(pair.getPublic().getEncoded()))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("JWT signing keys could not be loaded").hasNoCause();
        }
    }

    @Test
    void mismatchedAndWeakKeysAreRejected() throws Exception {
        KeyPair other = generate(2048);
        assertThatThrownBy(() -> configuration.jwtKeyPair(properties(
                encoded(pair.getPrivate().getEncoded()), encoded(other.getPublic().getEncoded()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("matching RSA key pair");
        KeyPair weak = generate(1024);
        assertThatThrownBy(() -> configuration.jwtKeyPair(properties(
                encoded(weak.getPrivate().getEncoded()), encoded(weak.getPublic().getEncoded()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 2048 bits");
    }

    private JwtProperties properties(String privateBase64, String publicBase64) {
        return new JwtProperties("https://pebble.local", "pebble-api", "test", privateBase64, publicBase64);
    }

    private static KeyPair generate(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private String encoded(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + encoded(bytes) + "\n-----END " + type + "-----\n";
    }
}
