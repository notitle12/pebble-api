package com.pebble.api.global.security;

import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** 인가·CORS·OpenAPI가 함께 사용하는 메서드/경로 계약. 미등록 요청은 거부한다. */
public final class SecurityEndpoints {
    private SecurityEndpoints() {}

    public enum Access { PUBLIC, USER, ADMIN, MASTER }
    public record Endpoint(HttpMethod method, String path, Access access) {}

    public static final List<Endpoint> API = List.of(
            new Endpoint(HttpMethod.POST, "/api/v1/auth/naver/authorization", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/naver/login", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/naver/withdrawal/cancel", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/token/refresh", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/logout", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/admin/auth/login", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/admin/auth/token/refresh", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/admin/auth/logout", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/admin-accounts", Access.MASTER),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/categories", Access.ADMIN),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/tags", Access.ADMIN),
            new Endpoint(HttpMethod.POST, "/api/v1/admin/categories", Access.ADMIN),
            new Endpoint(HttpMethod.POST, "/api/v1/admin/tags", Access.ADMIN),
            new Endpoint(HttpMethod.PATCH, "/api/v1/admin/categories/{id:[0-9]+}", Access.ADMIN),
            new Endpoint(HttpMethod.PATCH, "/api/v1/admin/tags/{id:[0-9]+}", Access.ADMIN),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/post-comments", Access.ADMIN),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/project-comments", Access.ADMIN),
            new Endpoint(HttpMethod.DELETE, "/api/v1/admin/post-comments/{id:[0-9]+}", Access.ADMIN),
            new Endpoint(HttpMethod.DELETE, "/api/v1/admin/project-comments/{id:[0-9]+}", Access.ADMIN),
            new Endpoint(HttpMethod.POST, "/api/v1/admin/admin-accounts", Access.MASTER),
            new Endpoint(HttpMethod.PATCH, "/api/v1/admin/admin-accounts/{adminId:[0-9]+}/status", Access.MASTER),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/members", Access.ADMIN),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/members/{memberId:[0-9]+}", Access.ADMIN),
            new Endpoint(HttpMethod.PATCH, "/api/v1/admin/members/{memberId:[0-9]+}/status", Access.ADMIN),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/posts", Access.ADMIN),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/projects", Access.ADMIN),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/posts/{id:[0-9]+}", Access.ADMIN),
            new Endpoint(HttpMethod.GET, "/api/v1/admin/projects/{id:[0-9]+}", Access.ADMIN),
            new Endpoint(HttpMethod.PUT, "/api/v1/admin/posts/{id:[0-9]+}/block", Access.ADMIN),
            new Endpoint(HttpMethod.PUT, "/api/v1/admin/projects/{id:[0-9]+}/block", Access.ADMIN),
            new Endpoint(HttpMethod.DELETE, "/api/v1/admin/posts/{id:[0-9]+}/block", Access.ADMIN),
            new Endpoint(HttpMethod.DELETE, "/api/v1/admin/projects/{id:[0-9]+}/block", Access.ADMIN),
            new Endpoint(HttpMethod.DELETE, "/api/v1/admin/posts/{id:[0-9]+}", Access.ADMIN),
            new Endpoint(HttpMethod.DELETE, "/api/v1/admin/projects/{id:[0-9]+}", Access.ADMIN),
            new Endpoint(HttpMethod.GET, "/api/v1/members/me", Access.USER),
            new Endpoint(HttpMethod.GET, "/api/v1/members/me/posts", Access.USER),
            new Endpoint(HttpMethod.GET, "/api/v1/members/me/boards", Access.USER),
            new Endpoint(HttpMethod.GET, "/api/v1/members/me/projects", Access.USER),
            new Endpoint(HttpMethod.GET, "/api/v1/members/me/profile/availability", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/members/me", Access.USER),
            new Endpoint(HttpMethod.GET, "/api/v1/categories", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/tags", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/members/me/profile", Access.USER),
            new Endpoint(HttpMethod.PATCH, "/api/v1/members/me/profile", Access.USER),
            new Endpoint(HttpMethod.GET, "/api/v1/posts/search", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/projects", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/projects/search", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/projects/{projectId:[0-9]+}", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/projects/{projectId:[0-9]+}/posts", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/members/{memberId:[0-9]+}/projects", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/posts", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/posts/{postId:[0-9]+}", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/blogs/{handle}", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/blogs/{handle}/posts", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/blogs/{handle}/posts/{postKey}", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/members/{memberId:[0-9]+}/posts", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/members/{memberId:[0-9]+}/boards", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/members/{memberId:[0-9]+}/boards/{boardId:[0-9]+}/posts", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/boards", Access.USER),
            new Endpoint(HttpMethod.PATCH, "/api/v1/boards/{boardId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/boards/{boardId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.GET, "/api/v1/posts/{postId:[0-9]+}/images/{imageId:[0-9]+}/content", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/posts/{postId:[0-9]+}/images", Access.USER),
            new Endpoint(HttpMethod.POST, "/api/v1/posts/{postId:[0-9]+}/images", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/posts/{postId:[0-9]+}/images/{imageId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.PUT, "/api/v1/posts/{postId:[0-9]+}/thumbnail", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/posts/{postId:[0-9]+}/thumbnail", Access.USER),
            new Endpoint(HttpMethod.POST, "/api/v1/projects/{projectId:[0-9]+}/media", Access.USER),
            new Endpoint(HttpMethod.PATCH, "/api/v1/projects/{projectId:[0-9]+}/media/{mediaId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/projects/{projectId:[0-9]+}/media/{mediaId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.POST, "/api/v1/posts", Access.USER),
            new Endpoint(HttpMethod.GET, "/api/v1/posts/{postId:[0-9]+}/comments", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/posts/{postId:[0-9]+}/comments/{commentId:[0-9]+}", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/projects/{projectId:[0-9]+}/comments", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/v1/projects/{projectId:[0-9]+}/comments/{commentId:[0-9]+}", Access.PUBLIC),
            new Endpoint(HttpMethod.POST, "/api/v1/posts/{postId:[0-9]+}/comments", Access.USER),
            new Endpoint(HttpMethod.POST, "/api/v1/projects/{projectId:[0-9]+}/comments", Access.USER),
            new Endpoint(HttpMethod.PATCH, "/api/v1/posts/{postId:[0-9]+}/comments/{commentId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.PATCH, "/api/v1/projects/{projectId:[0-9]+}/comments/{commentId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/posts/{postId:[0-9]+}/comments/{commentId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/projects/{projectId:[0-9]+}/comments/{commentId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.PUT, "/api/v1/posts/{postId:[0-9]+}/like", Access.USER),
            new Endpoint(HttpMethod.PUT, "/api/v1/projects/{projectId:[0-9]+}/like", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/posts/{postId:[0-9]+}/like", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/projects/{projectId:[0-9]+}/like", Access.USER),
            new Endpoint(HttpMethod.PATCH, "/api/v1/posts/{postId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/posts/{postId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.POST, "/api/v1/projects", Access.USER),
            new Endpoint(HttpMethod.PATCH, "/api/v1/projects/{projectId:[0-9]+}", Access.USER),
            new Endpoint(HttpMethod.DELETE, "/api/v1/projects/{projectId:[0-9]+}", Access.USER)
    );

    static RequestMatcher[] csrfExemptions() {
        // OAuth state 또는 CookieOriginFilter로 보호하는 POST에 한정한다.
        return new RequestMatcher[] {
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/naver/authorization"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/naver/login"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/naver/withdrawal/cancel"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/token/refresh"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/logout"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/admin/auth/login"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/admin/auth/token/refresh"),
                        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/admin/auth/logout")
        };
    }


    static void authorize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authorize,
                          boolean documentationEnabled) {
        if (documentationEnabled) {
            authorize.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs.yaml",
                    "/v3/api-docs/swagger-config", "/v3/api-docs/member", "/v3/api-docs/admin",
                    "/swagger-ui.html", "/swagger-ui/**").permitAll();
        }
        for (Endpoint endpoint : API) {
            var rule = authorize.requestMatchers(endpoint.method(), endpoint.path());
            switch (endpoint.access()) {
                case PUBLIC -> rule.permitAll();
                case USER -> rule.hasRole("USER");
                case ADMIN -> rule.hasAnyRole("MANAGER", "MASTER");
                case MASTER -> rule.hasRole("MASTER");
            }
        }
        authorize.anyRequest().denyAll();
    }
}
