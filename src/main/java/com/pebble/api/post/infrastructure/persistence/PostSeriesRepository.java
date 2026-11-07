package com.pebble.api.post.infrastructure.persistence;

import com.pebble.api.post.domain.PostSeries;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostSeriesRepository extends JpaRepository<PostSeries, Long> {
    long countByOwnerMemberId(Long ownerMemberId);
}
