package com.pebble.api.project.infrastructure.persistence;

import com.pebble.api.project.domain.ProjectFeature;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectFeatureRepository extends JpaRepository<ProjectFeature, Long> {
    List<ProjectFeature> findByProjectIdOrderByDisplayOrderAscIdAsc(long projectId);
    @Modifying
    @Query("delete from ProjectFeature feature where feature.project.id = :projectId")
    void deleteForProject(@Param("projectId") long projectId);
}
