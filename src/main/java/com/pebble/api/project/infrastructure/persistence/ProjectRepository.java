package com.pebble.api.project.infrastructure.persistence;

import com.pebble.api.project.domain.Project;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

public interface ProjectRepository extends JpaRepository<Project, Long>, JpaSpecificationExecutor<Project> {
    java.util.List<Project> findByOwnerIdAndVisibilityNotOrderByDisplayOrderAscIdAsc(long ownerId, com.pebble.api.project.domain.ProjectVisibility visibility);

    @Query("select p.owner.id from Project p where p.id = :id")
    Optional<Long> findOwnerIdForManagement(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") long id);

    @Override
    @EntityGraph(attributePaths = "owner")
    Optional<Project> findById(Long id);

    @Override
    @EntityGraph(attributePaths = "owner")
    Page<Project> findAll(Specification<Project> specification, Pageable pageable);

    @Modifying
    @Query("delete from Project p where p.owner.id=:memberId")
    int deleteForMember(@Param("memberId") long memberId);
    @Query(value = """
            select p.id from project p join member owner on owner.id=p.owner_member_id
            join (select l.project_id as content_id, count(*) as likes
                  from project_like l join member voter on voter.id=l.member_id
                  where l.deleted_at is null and voter.status <> 'WITHDRAWAL_PENDING'
                  group by l.project_id) ranking on ranking.content_id=p.id
            where p.visibility_status='PUBLIC' and not p.is_blocked
              and owner.status <> 'WITHDRAWAL_PENDING'
            order by ranking.likes desc, p.published_at desc nulls last, p.id desc
            """, countQuery = """
            select count(*) from project p join member owner on owner.id=p.owner_member_id
            join (select l.project_id as content_id, count(*) as likes
                  from project_like l join member voter on voter.id=l.member_id
                  where l.deleted_at is null and voter.status <> 'WITHDRAWAL_PENDING'
                  group by l.project_id) ranking on ranking.content_id=p.id
            where p.visibility_status='PUBLIC' and not p.is_blocked
              and owner.status <> 'WITHDRAWAL_PENDING'
            """, nativeQuery = true)
    Page<Long> findPopularIds(Pageable pageable);

}
