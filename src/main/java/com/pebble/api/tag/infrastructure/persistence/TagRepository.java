package com.pebble.api.tag.infrastructure.persistence;

import com.pebble.api.tag.domain.Tag;
import com.pebble.api.tag.domain.TagStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByStatusOrderByDisplayOrderAscIdAsc(TagStatus status);
    List<Tag> findAllByOrderByDisplayOrderAscIdAsc();
}
