package com.pebble.api.project.infrastructure.persistence;

import com.pebble.api.project.domain.ProjectLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectLinkRepository extends JpaRepository<ProjectLink, Long> {
    List<ProjectLink> findByProjectIdOrderByDisplayOrderAscIdAsc(long projectId);
    @Modifying
    @Query("delete from ProjectLink link where link.project.id = :projectId")
    void deleteForProject(@Param("projectId") long projectId);
}
