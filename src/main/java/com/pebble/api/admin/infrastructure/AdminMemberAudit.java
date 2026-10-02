package com.pebble.api.admin.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AdminMemberAudit {
    private static final Logger AUDIT = LoggerFactory.getLogger("pebble.admin.audit");

    public void record(String action, String outcome, long actorId, Long targetId, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip == null || !ip.matches("[0-9a-fA-F:.]{1,64}")) ip = "unknown";
        AUDIT.info("action={} outcome={} adminId={} targetId={} ip={} dataType=member download=false",
                action, outcome, actorId, targetId == null ? "none" : targetId, ip);
    }
}
