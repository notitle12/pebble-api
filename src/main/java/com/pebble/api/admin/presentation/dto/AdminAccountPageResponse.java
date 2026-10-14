package com.pebble.api.admin.presentation.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record AdminAccountPageResponse(List<AdminAccountResponse> content, int page, int size, long totalElements,
                                      int totalPages, boolean hasNext, boolean hasPrevious) {
    public static AdminAccountPageResponse from(Page<AdminAccountResponse> page) {
        return new AdminAccountPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.hasNext(), page.hasPrevious());
    }
}
