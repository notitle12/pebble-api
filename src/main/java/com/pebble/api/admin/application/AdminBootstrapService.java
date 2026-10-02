package com.pebble.api.admin.application;

import com.pebble.api.admin.domain.AdminAccount;
import com.pebble.api.admin.domain.AdminRole;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

@Service
public class AdminBootstrapService {
    private static final long ADVISORY_LOCK_KEY = 20261002L;
    private static final Pattern LOGIN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,99}");

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
        if (loginId == null || !LOGIN_ID.matcher(loginId).matches()) {
            throw new IllegalArgumentException("Invalid admin bootstrap configuration");
        }
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Invalid admin bootstrap configuration");
        int count = 0;
        for (int i = 0; i < password.length();) {
            char ch = password.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= password.length() || !Character.isLowSurrogate(password.charAt(i + 1))) {
                    throw new IllegalArgumentException("Invalid admin bootstrap configuration");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("Invalid admin bootstrap configuration");
            }
            int codePoint = password.codePointAt(i);
            if (Character.isISOControl(codePoint) || codePoint == 0) {
                throw new IllegalArgumentException("Invalid admin bootstrap configuration");
            }
            count++;
            i += Character.charCount(codePoint);
        }
        if (count < 12 || count > 128) {
            throw new IllegalArgumentException("Invalid admin bootstrap configuration");
        }
    }
}
