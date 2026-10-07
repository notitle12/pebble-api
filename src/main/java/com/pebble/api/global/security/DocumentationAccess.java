package com.pebble.api.global.security;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** prod와 함께 켜거나 명시적 개발 프로필 없이 문서 설정만 켜도 외부 접근을 허용하지 않는다. */
@Component
public class DocumentationAccess {
    private final Environment environment;

    public DocumentationAccess(Environment environment) {
        this.environment = environment;
    }

    public boolean enabled() {
        return environment.acceptsProfiles(Profiles.of("api-docs & !prod"))
                && environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false);
    }
}
