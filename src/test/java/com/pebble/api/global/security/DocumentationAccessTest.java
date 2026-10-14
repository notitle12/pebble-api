package com.pebble.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DocumentationAccessTest {
    @Test
    void documentationRequiresExplicitProfileAndEnabledFlag() {
        var env = new MockEnvironment().withProperty("springdoc.api-docs.enabled", "true");
        var access = new DocumentationAccess(env);
        assertThat(access.enabled()).isFalse();
        env.setActiveProfiles("local", "api-docs");
        assertThat(access.enabled()).isTrue();
        env.setProperty("springdoc.api-docs.enabled", "false");
        assertThat(access.enabled()).isFalse();
    }

    @Test
    void productionAlwaysDeniesDocumentationEvenWithDevelopmentProfile() {
        var env = new MockEnvironment().withProperty("springdoc.api-docs.enabled", "true");
        env.setActiveProfiles("prod", "api-docs");
        assertThat(new DocumentationAccess(env).enabled()).isFalse();
    }
}
