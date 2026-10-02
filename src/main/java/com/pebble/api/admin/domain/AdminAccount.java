package com.pebble.api.admin.domain;

import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "admin_account")
@Check(name = "ck_admin_account_role", constraints = "role in ('MANAGER', 'MASTER')")
@Check(name = "ck_admin_account_status", constraints = "status in ('ACTIVE', 'INACTIVE')")
public class AdminAccount {

    @Id
    private Long id;

    @Column(name = "login_id", nullable = false, length = 100, unique = true)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdminRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdminStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminAccount() {
    }

    public AdminAccount(String loginId, String passwordHash, AdminRole role) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = AdminStatus.ACTIVE;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) id = TsidGenerator.generate();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getPasswordHash() { return passwordHash; }
    public AdminRole getRole() { return role; }
    public AdminStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
