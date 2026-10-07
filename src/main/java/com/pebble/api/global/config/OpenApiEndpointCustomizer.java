package com.pebble.api.global.config;

import com.pebble.api.global.security.SecurityEndpoints;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/** 컨트롤러의 posts|projects 같은 제한된 변수도 프론트가 사용하는 실제 경로로 펼친다. */
final class OpenApiEndpointCustomizer implements GlobalOpenApiCustomizer {
    private final RequestMappingHandlerMapping mappings;

    OpenApiEndpointCustomizer(RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    @Override
    public void customise(OpenAPI api) {
        var expanded = new Paths();
        api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            var httpMethod = HttpMethod.valueOf(method.name());
            var patterns = mappings.getHandlerMethods().keySet().stream()
                    .filter(mapping -> mapping.getMethodsCondition().getMethods().stream()
                            .anyMatch(value -> value.name().equals(method.name())))
                    .flatMap(mapping -> mapping.getPatternValues().stream())
                    .filter(pattern -> template(pattern).equals(path))
                    .map(pattern -> PathPatternParser.defaultInstance.parse(pattern)).toList();
            var endpoints = SecurityEndpoints.API.stream().filter(endpoint -> endpoint.method().equals(httpMethod)
                    && patterns.stream().anyMatch(pattern -> pattern.matches(PathContainer.parsePath(
                            endpoint.path().replaceAll("\\{[^}]+}", "1"))))).toList();
            if (endpoints.isEmpty()) throw new IllegalStateException("Missing API security policy: " + method + " " + path);
            for (var endpoint : endpoints) {
                var targetPath = template(endpoint.path());
                var copy = Json.mapper().convertValue(operation, Operation.class);
                renameParameters(copy, path, targetPath);
                if (!targetPath.equals(path)) copy.setOperationId(operation.getOperationId() + "_" + targetPath.replaceAll("[^a-zA-Z0-9]", "_"));
                copy.setSecurity(endpoint.access() == SecurityEndpoints.Access.PUBLIC ? List.of()
                        : List.of(new SecurityRequirement().addList("bearerAuth")));
                copy.addExtension("x-pebble-access", endpoint.access().name());
                expanded.computeIfAbsent(targetPath, ignored -> new PathItem()).operation(method, copy);
            }
        }));
        api.setPaths(expanded);
    }

    private static String template(String path) {
        return path.replaceAll(":[^}]+", "");
    }

    private static void renameParameters(Operation operation, String original, String target) {
        if (operation.getParameters() == null) return;
        var before = original.split("/");
        var after = target.split("/");
        var parameters = new ArrayList<>(operation.getParameters());
        for (int i = 0; i < before.length; i++) {
            if (!before[i].startsWith("{")) continue;
            var name = before[i].substring(1, before[i].length() - 1);
            var replacement = after[i];
            parameters.removeIf(parameter -> "path".equals(parameter.getIn()) && name.equals(parameter.getName())
                    && !replacement.startsWith("{"));
            if (replacement.startsWith("{")) parameters.stream()
                    .filter(parameter -> "path".equals(parameter.getIn()) && name.equals(parameter.getName()))
                    .forEach(parameter -> parameter.setName(replacement.substring(1, replacement.length() - 1)));
        }
        operation.setParameters(parameters);
    }
}
