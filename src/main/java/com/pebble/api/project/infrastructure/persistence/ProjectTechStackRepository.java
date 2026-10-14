package com.pebble.api.project.infrastructure.persistence;

import com.pebble.api.project.domain.ProjectTechStack;
import com.pebble.api.project.domain.ProjectTechStackId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectTechStackRepository extends JpaRepository<ProjectTechStack, ProjectTechStackId> {
    @Query("select pt from ProjectTechStack pt join fetch pt.techStack where pt.project.id in :projectIds order by pt.displayOrder, pt.techStack.id")
    List<ProjectTechStack> findForProjects(@Param("projectIds") Collection<Long> projectIds);
    @Modifying
    @Query("delete from ProjectTechStack pt where pt.project.id = :projectId")
    void deleteForProject(@Param("projectId") long projectId);

    @Query(value = "select distinct pt.tech_stack_id from project_tech_stack pt join project p on p.id = pt.project_id "
            + "join member m on m.id = p.owner_member_id where p.visibility_status = 'PUBLIC' "
            + "and not p.is_blocked and m.status <> 'WITHDRAWAL_PENDING'", nativeQuery = true)
    List<Long> findPublicTechStackIds();
}
