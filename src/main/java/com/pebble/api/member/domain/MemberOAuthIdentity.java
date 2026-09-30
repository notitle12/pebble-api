package com.pebble.api.member.domain;

import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "member_oauth_identity")
@Check(name = "ck_member_oauth_identity_provider", constraints = "provider in ('NAVER')")
public class MemberOAuthIdentity {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private OAuthProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255, updatable = false)
    private String providerSubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MemberOAuthIdentity() {
    }

    public MemberOAuthIdentity(Member member, OAuthProvider provider, String providerSubject) {
        this.member = member;
        this.provider = provider;
        this.providerSubject = providerSubject;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public OAuthProvider getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
