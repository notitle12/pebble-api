package com.pebble.api.project.infrastructure.persistence;

import com.pebble.api.project.domain.ProjectMedia;
import com.pebble.api.project.domain.MediaRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMediaRepository extends JpaRepository<ProjectMedia, Long> {
    List<ProjectMedia> findByProjectIdOrderByDisplayOrderAscIdAsc(long projectId);

    boolean existsByProjectIdAndMediaRole(long projectId, MediaRole mediaRole);

    @Modifying
    @Query("delete from ProjectMedia m where m.projectId=:id")
    int deleteForProject(@Param("id") long id);
}
