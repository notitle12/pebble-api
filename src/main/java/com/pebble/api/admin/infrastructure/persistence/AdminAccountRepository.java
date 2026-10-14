package com.pebble.api.admin.infrastructure.persistence;

import com.pebble.api.admin.domain.AdminAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, Long> {
    Optional<AdminAccount> findByLoginId(String loginId);
    boolean existsByRole(com.pebble.api.admin.domain.AdminRole role);
    boolean existsByLoginId(String loginId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select a from AdminAccount a where a.loginId = :loginId")
    Optional<AdminAccount> findForAuthenticationByLoginId(@Param("loginId") String loginId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select a from AdminAccount a where a.id = :id")
    Optional<AdminAccount> findForAuthenticationById(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AdminAccount a where a.id = :id and a.role = com.pebble.api.admin.domain.AdminRole.MANAGER")
    Optional<AdminAccount> findManagerForUpdate(@Param("id") long id);
}
