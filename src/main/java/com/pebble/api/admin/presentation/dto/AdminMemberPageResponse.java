package com.pebble.api.admin.presentation.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record AdminMemberPageResponse(List<AdminMemberResponse> content, int page, int size, long totalElements,
                                      int totalPages, boolean hasNext, boolean hasPrevious) {
    public static AdminMemberPageResponse from(Page<AdminMemberResponse> page) {
        return new AdminMemberPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.hasNext(), page.hasPrevious());
    }
}
