package com.pebble.api.admin.application;

import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminCredentials;
import com.pebble.api.admin.domain.AdminError;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.auth.application.AdminRefreshTokenService;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 목록도 MASTER의 상태를 잠금 아래에서 확인하므로 일반 트랜잭션을 사용한다.
@Transactional
public class AdminAccountService {
    private final AdminAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AdminRefreshTokenService sessions;

    public Page<AdminAccount> list(long actorId, Pageable pageable) {
        requireMaster(actorId);
        return accounts.findAll(pageable);
    }

    public AdminAccount create(long actorId, String loginId, String initialPassword) {
        requireMaster(actorId);
        if (!AdminCredentials.validLoginId(loginId) || !AdminCredentials.validInitialPassword(initialPassword))
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        if (accounts.existsByLoginId(loginId)) throw new ApplicationException(AdminError.DUPLICATE_RESOURCE);
        try {
            return accounts.saveAndFlush(new AdminAccount(loginId, passwords.encode(initialPassword), AdminRole.MANAGER));
        } catch (DataIntegrityViolationException exception) {
            // 최종 고유 제약은 여러 MASTER가 같은 ID를 동시에 생성하는 요청도 보호한다.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && "admin_account_login_id_key".equals(violation.getConstraintName()))
                    throw new ApplicationException(AdminError.DUPLICATE_RESOURCE);
            }
            throw exception;
        }
    }

    public AdminAccount changeStatus(long actorId, long targetId, AdminStatus status) {
        requireMaster(actorId);
        if (actorId == targetId) throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        if (status == null) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        AdminAccount target = accounts.findManagerForUpdate(targetId)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
        // 로그인·refresh의 읽기 잠금과 직렬화한다. 재활성화도 과거 세션을 복구하지 않는다.
        sessions.revokeAll(targetId);
        target.changeManagerStatus(status);
        accounts.flush();
        return target;
    }

    private void requireMaster(long actorId) {
        AdminAccount actor = accounts.findForAuthenticationById(actorId)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.INVALID_TOKEN));
        if (actor.getStatus() != AdminStatus.ACTIVE) throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
        if (actor.getRole() != AdminRole.MASTER) throw new ApplicationException(GlobalErrorCode.INSUFFICIENT_ROLE);
    }
}
