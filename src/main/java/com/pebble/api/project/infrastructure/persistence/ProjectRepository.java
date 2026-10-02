package com.pebble.api.project.infrastructure.persistence;

import com.pebble.api.project.domain.Project;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long>, JpaSpecificationExecutor<Project> {
    @Query("select p.owner.id from Project p where p.id = :id")
    Optional<Long> findOwnerIdForManagement(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p join fetch p.owner where p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") long id);

    @Override
    @EntityGraph(attributePaths = "owner")
    Optional<Project> findById(Long id);
}
