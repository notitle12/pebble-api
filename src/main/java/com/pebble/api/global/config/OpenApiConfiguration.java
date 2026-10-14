package com.pebble.api.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("api-docs & !prod")
public class OpenApiConfiguration {
    @Bean
    OpenAPI pebbleOpenApi() {
        return new OpenAPI().info(new Info().title("Pebble API").version("v1")
                .description("프론트 협업용 개발 API. ID는 문자열로 처리합니다. "
                        + "일반 회원은 USER, 관리자는 MANAGER/MASTER Bearer 토큰을 사용합니다. "
                        + "쿠키 인증 경로는 허용 Origin과 OAuth state 검증이 필요합니다. "
                        + "변경 API의 CSRF 정책은 docs/SECURITY.md를 확인하세요."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    GroupedOpenApi memberApi() {
        return GroupedOpenApi.builder().group("member").pathsToMatch("/api/v1/**")
                .pathsToExclude("/api/v1/admin/**").build();
    }

    @Bean
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin").pathsToMatch("/api/v1/admin/**").build();
    }

    @Bean
    GlobalOpenApiCustomizer endpointSecurity(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings) {
        return new OpenApiEndpointCustomizer(mappings);
    }
}
