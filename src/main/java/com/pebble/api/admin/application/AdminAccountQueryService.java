package com.pebble.api.admin.application;

import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AdminAccountQueryService {
    private final AdminAccountRepository repository;

    public AdminAccountQueryService(AdminAccountRepository repository) {
        this.repository = repository;
    }

    public boolean findActive(long id, String role) {
        // 비활성 상태를 보고 세션을 폐기하는 동안 재활성화·새 로그인과 경합하지 않는다.
        return repository.findForAuthenticationById(id)
                .filter(account -> account.getStatus() == AdminStatus.ACTIVE)
                .filter(account -> account.getRole().name().equals(role))
                .isPresent();
    }

    public Optional<AdminAccount> findForLogin(String loginId) {
        return repository.findForAuthenticationByLoginId(loginId);
    }

    public Optional<AdminAccount> findForRefresh(long id) {
        return repository.findForAuthenticationById(id);
    }
}
