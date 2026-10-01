package com.pebble.api.project.application;

import com.pebble.api.project.infrastructure.persistence.ProjectTagRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectQueryService {
    private final ProjectTagRepository projectTags;

    public List<Long> findPublicTagIds() {
        return projectTags.findPublicTagIds();
    }
}
