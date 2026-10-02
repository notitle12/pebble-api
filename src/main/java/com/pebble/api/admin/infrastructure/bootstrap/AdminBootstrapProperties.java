package com.pebble.api.admin.infrastructure.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pebble.admin.bootstrap")
public class AdminBootstrapProperties {
    private String loginId;
    private String password;

    public String getLoginId() { return loginId; }
    public void setLoginId(String loginId) { this.loginId = loginId; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    @Override
    public String toString() {
        return "AdminBootstrapProperties[redacted]";
    }
}
