package com.pebble.api.update.infrastructure;
import com.pebble.api.update.domain.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ServiceUpdateRepository extends JpaRepository<ServiceUpdate, Long> {
    @Query("select u from ServiceUpdate u where u.deletedAt is null and (:type is null or u.type = :type) and u.status = com.pebble.api.update.domain.UpdateStatus.PUBLISHED")
    Page<ServiceUpdate> findPublic(@Param("type") UpdateType type, Pageable page);
    @Query("select u from ServiceUpdate u where u.deletedAt is null and (:type is null or u.type = :type) and (:status is null or u.status = :status)")
    Page<ServiceUpdate> findManagement(@Param("type") UpdateType type, @Param("status") UpdateStatus status, Pageable page);
    Optional<ServiceUpdate> findByIdAndDeletedAtIsNull(long id);
    Optional<ServiceUpdate> findByIdAndDeletedAtIsNullAndStatus(long id, UpdateStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ServiceUpdate u where u.id = :id and u.deletedAt is null")
    Optional<ServiceUpdate> lockById(@Param("id") long id);
}
