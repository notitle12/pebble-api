package com.pebble.api.project.infrastructure.persistence;

import com.pebble.api.project.domain.ProjectTag;
import com.pebble.api.project.domain.ProjectTagId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectTagRepository extends JpaRepository<ProjectTag, ProjectTagId> {
    @Query("select pt from ProjectTag pt join fetch pt.tag where pt.project.id in :projectIds order by pt.displayOrder, pt.tag.id")
    List<ProjectTag> findForProjects(@Param("projectIds") Collection<Long> projectIds);
    @Modifying
    @Query("delete from ProjectTag pt where pt.project.id = :projectId")
    void deleteForProject(@Param("projectId") long projectId);

    @Query(value = "select distinct pt.tag_id from project_tag pt join project p on p.id = pt.project_id "
            + "join member m on m.id = p.owner_member_id where p.visibility_status = 'PUBLIC' "
            + "and not p.is_blocked and m.status <> 'WITHDRAWAL_PENDING'", nativeQuery = true)
    List<Long> findPublicTagIds();
}
