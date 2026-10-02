package com.pebble.api.admin.presentation.dto;

import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.domain.AdminStatus;
import java.time.Instant;

public record AdminAccountResponse(String id, String loginId, AdminRole role, AdminStatus status,
                                   Instant createdAt, Instant updatedAt) {
    public static AdminAccountResponse from(AdminAccount account) {
        return new AdminAccountResponse(account.getId().toString(), account.getLoginId(), account.getRole(),
                account.getStatus(), account.getCreatedAt(), account.getUpdatedAt());
    }
}
