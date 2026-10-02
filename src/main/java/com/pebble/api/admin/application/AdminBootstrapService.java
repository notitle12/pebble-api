package com.pebble.api.admin.application;

import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.admin.domain.AdminCredentials;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

@Service
public class AdminBootstrapService {
    private static final long ADVISORY_LOCK_KEY = 20261002L;

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    public AdminBootstrapService(AdminAccountRepository repository, PasswordEncoder passwordEncoder,
                                 EntityManager entityManager) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.entityManager = entityManager;
    }

    @Transactional
    public boolean bootstrap(String loginId, String password) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", ADVISORY_LOCK_KEY).getSingleResult();
        if (repository.existsByRole(AdminRole.MASTER)) return false;
        validate(loginId, password);
        repository.saveAndFlush(new AdminAccount(loginId, passwordEncoder.encode(password), AdminRole.MASTER));
        return true;
    }

    private static void validate(String loginId, String password) {
        if (!AdminCredentials.validLoginId(loginId) || !AdminCredentials.validInitialPassword(password))
            throw new IllegalArgumentException("Invalid admin bootstrap configuration");
    }

}
