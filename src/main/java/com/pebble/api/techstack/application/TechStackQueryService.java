package com.pebble.api.techstack.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.project.application.ProjectQueryService;
import com.pebble.api.techstack.domain.TechStack;
import com.pebble.api.techstack.domain.TechStackError;
import com.pebble.api.techstack.domain.TechStackStatus;
import com.pebble.api.techstack.infrastructure.persistence.TechStackRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TechStackQueryService {

    private final TechStackRepository techStacks;
    private final ProjectQueryService projects;
    private final EntityManager entities;

    @Transactional
    public List<TechStack> resolveForContent(List<Long> ids, Set<Long> existingIds) {
        if (ids.isEmpty()) return List.of();
        List<TechStack> resolved = techStacks.findForSelection(ids);
        if (resolved.size() != ids.size()) throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        for (TechStack techStack : resolved) {
            // 기존 연결 조회의 영속성 캐시를 새 선택의 상태 근거로 사용하지 않는다.
            entities.refresh(techStack);
            if (techStack.getStatus() != TechStackStatus.ACTIVE && !existingIds.contains(techStack.getId())) {
                throw new ApplicationException(TechStackError.INACTIVE_TECH_STACK);
            }
        }
        Map<Long, TechStack> byId = new HashMap<>();
        resolved.forEach(techStack -> byId.put(techStack.getId(), techStack));
        return ids.stream().map(byId::get).toList();
    }

    public List<TechStack> findForManagement() {
        return techStacks.findAllByOrderByDisplayOrderAscIdAsc();
    }

    public List<TechStack> findPublicTechStacks() {
        Set<Long> used = new HashSet<>(projects.findPublicTechStackIds());
        return techStacks.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .filter(techStack -> techStack.getStatus() == TechStackStatus.ACTIVE || used.contains(techStack.getId())).toList();
    }
}
