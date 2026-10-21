package com.pebble.api.report.infrastructure.persistence;
import com.pebble.api.report.domain.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ReportRepository extends JpaRepository<ContentReport,Long> {
    Optional<ContentReport> findByTargetTypeAndTargetIdAndReporterId(ReportTarget targetType,long targetId,long reporterId);
    @Query("select r from ContentReport r where (:status is null or r.status=:status) and (:targetType is null or r.targetType=:targetType)")
    Page<ContentReport> findForManagement(@Param("status") ReportStatus status,@Param("targetType") ReportTarget targetType,Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ContentReport r where r.id=:id")
    Optional<ContentReport> findForUpdate(@Param("id") long id);
}
