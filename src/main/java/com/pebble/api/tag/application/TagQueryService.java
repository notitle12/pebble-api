package com.pebble.api.tag.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.post.infrastructure.persistence.PostRepository;
import com.pebble.api.project.application.ProjectQueryService;
import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagError;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.infrastructure.persistence.TagRepository;
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
public class TagQueryService {

    private final TagRepository tags;
    private final PostRepository posts;
    private final ProjectQueryService projects;
    private final EntityManager entities;

    @Transactional
    public List<Tag> resolveForPost(List<Long> ids, Set<Long> existingIds) {
        return resolveForContent(ids, existingIds);
    }

    @Transactional
    public List<Tag> resolveForContent(List<Long> ids, Set<Long> existingIds) {
        if (ids.isEmpty()) return List.of();
        List<Tag> resolved = tags.findForSelection(ids);
        if (resolved.size() != ids.size()) throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        for (Tag tag : resolved) {
            // 기존 연결 조회의 영속성 캐시를 새 선택의 상태 근거로 사용하지 않는다.
            entities.refresh(tag);
            if (tag.getStatus() != TagStatus.ACTIVE && !existingIds.contains(tag.getId())) {
                throw new ApplicationException(TagError.INACTIVE_TAG);
            }
        }
        Map<Long, Tag> byId = new HashMap<>();
        resolved.forEach(tag -> byId.put(tag.getId(), tag));
        return ids.stream().map(byId::get).toList();
    }

    public List<Tag> findForManagement() {
        return tags.findAllByOrderByDisplayOrderAscIdAsc();
    }

    public List<Tag> findPublicTags() {
        Set<Long> used = new HashSet<>(posts.findPublicTagIds());
        used.addAll(projects.findPublicTagIds());
        return tags.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .filter(tag -> tag.getStatus() == TagStatus.ACTIVE || used.contains(tag.getId())).toList();
    }
}
