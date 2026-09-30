package com.pebble.api.member.domain;

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
@Table(name = "member")
@Check(name = "ck_member_status", constraints = "status in ('ACTIVE', 'SUSPENDED', 'WITHDRAWAL_PENDING')")
@Check(name = "ck_member_withdrawal_timestamps", constraints = "((status = 'WITHDRAWAL_PENDING' and withdrawal_requested_at is not null and withdrawal_scheduled_at is not null) or (status <> 'WITHDRAWAL_PENDING' and withdrawal_requested_at is null and withdrawal_scheduled_at is null))")
public class Member {

    @Id
    private Long id;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(name = "profile_image_url", length = 2048)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    @Column(name = "withdrawal_requested_at")
    private Instant withdrawalRequestedAt;

    @Column(name = "withdrawal_scheduled_at")
    private Instant withdrawalScheduledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Member() {
    }

    public Member(String nickname, String profileImageUrl, MemberStatus status,
                  Instant withdrawalRequestedAt, Instant withdrawalScheduledAt) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.status = status;
        this.withdrawalRequestedAt = withdrawalRequestedAt;
        this.withdrawalScheduledAt = withdrawalScheduledAt;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public Instant getWithdrawalRequestedAt() {
        return withdrawalRequestedAt;
    }

    public Instant getWithdrawalScheduledAt() {
        return withdrawalScheduledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
