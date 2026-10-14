package com.pebble.api.auth.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AdminAuthAudit {
    private static final Logger AUDIT = LoggerFactory.getLogger("pebble.admin.audit");
    public void record(String action, String outcome, Long adminId, HttpServletRequest request) {
        // 프록시 헤더와 요청 본문을 기록하지 않는다. 네트워크 경계의 주소만 사용한다.
        String ip = request.getRemoteAddr();
        if (ip == null || !ip.matches("[0-9a-fA-F:.]{1,64}")) ip = "unknown";
        AUDIT.info("action={} outcome={} adminId={} ip={} dataType=admin_session download=false",
                action, outcome, adminId == null ? "unknown" : adminId, ip);
    }
}
