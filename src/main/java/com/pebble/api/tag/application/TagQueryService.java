package com.pebble.api.tag.application;

import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
import com.pebble.api.tag.infrastructure.persistence.TagRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagQueryService {

    private final TagRepository tags;

    public List<Tag> findPublicTags() {
        return tags.findByStatusOrderByDisplayOrderAscIdAsc(TagStatus.ACTIVE);
    }
}
