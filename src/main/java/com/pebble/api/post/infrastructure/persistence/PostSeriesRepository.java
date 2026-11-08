package com.pebble.api.post.infrastructure.persistence;

import com.pebble.api.post.domain.PostSeries;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PostSeriesRepository extends JpaRepository<PostSeries, Long> {
    long countByOwnerMemberId(Long ownerMemberId);
    List<PostSeries> findByOwnerMemberIdOrderByDisplayOrderAscIdAsc(Long ownerMemberId);
}
