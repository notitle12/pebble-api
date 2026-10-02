package com.pebble.api.admin.infrastructure.bootstrap;

import com.pebble.api.admin.application.AdminBootstrapService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminBootstrapProperties.class)
@ConditionalOnProperty(name = "pebble.admin.bootstrap.enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {
    private final AdminBootstrapProperties properties;
    private final AdminBootstrapService service;

    public AdminBootstrapRunner(AdminBootstrapProperties properties, AdminBootstrapService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.bootstrap(properties.getLoginId(), properties.getPassword());
    }
}
