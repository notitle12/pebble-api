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

    @Column(name = "blog_name", length = 100, unique = true)
    private String blogName;

    @Column(length = 30, unique = true)
    private String handle;

    @Column(name = "profile_completed_at")
    private Instant profileCompletedAt;

    @Column(name = "nickname_changed_at")
    private Instant nicknameChangedAt;

    @Column(name = "blog_name_changed_at")
    private Instant blogNameChangedAt;

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
        if (nicknameChangedAt == null) nicknameChangedAt = now;
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

    public void completeProfile(String blogName, String handle, String nickname, Instant now) {
        if (profileCompletedAt != null) throw new IllegalStateException("Profile is already completed");
        this.blogName = blogName;
        this.handle = handle;
        this.nickname = nickname;
        this.profileCompletedAt = now;
        this.nicknameChangedAt = now;
        this.blogNameChangedAt = now;
    }

    public void changeNickname(String nickname, Instant now) {
        this.nickname = nickname;
        this.nicknameChangedAt = now;
    }

    public void changeBlogName(String blogName, Instant now) {
        this.blogName = blogName;
        this.blogNameChangedAt = now;
    }

    public String getBlogName() { return blogName; }
    public String getHandle() { return handle; }
    public Instant getProfileCompletedAt() { return profileCompletedAt; }
    public Instant getNicknameChangedAt() { return nicknameChangedAt; }
    public Instant getBlogNameChangedAt() { return blogNameChangedAt; }
    public boolean isProfileCompleted() { return profileCompletedAt != null; }

    public String getNickname() {
        return nickname;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public void changeOperationalStatus(MemberStatus status) {
        if (this.status == MemberStatus.WITHDRAWAL_PENDING
                || status == null || status == MemberStatus.WITHDRAWAL_PENDING) {
            throw new IllegalStateException("Withdrawal status cannot be changed by an administrator");
        }
        this.status = status;
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
