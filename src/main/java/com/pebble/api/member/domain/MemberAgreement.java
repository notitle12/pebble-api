package com.pebble.api.member.domain;

import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "member_agreement", uniqueConstraints = @UniqueConstraint(name = "uk_member_agreement_versions", columnNames = {"member_id", "terms_version", "privacy_version"}))
public class MemberAgreement {
    public static final String TERMS_VERSION = "2026-10-21";
    public static final String PRIVACY_VERSION = "2026-10-21";
    @Id private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false)
    private Member member;
    @Column(name = "terms_version", nullable = false, length = 32, updatable = false)
    private String termsVersion;
    @Column(name = "privacy_version", nullable = false, length = 32, updatable = false)
    private String privacyVersion;
    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;
    @Column(name = "age_confirmed", nullable = false, updatable = false)
    private boolean ageConfirmed;
    protected MemberAgreement() {}
    MemberAgreement(Member member, Instant now) {
        this.id = TsidGenerator.generate();
        this.member = member;
        this.termsVersion = TERMS_VERSION;
        this.privacyVersion = PRIVACY_VERSION;
        this.acceptedAt = java.util.Objects.requireNonNull(now);
        this.ageConfirmed = true;
    }
    public boolean isCurrent() { return TERMS_VERSION.equals(termsVersion) && PRIVACY_VERSION.equals(privacyVersion) && ageConfirmed; }
    public Instant getAcceptedAt() { return acceptedAt; }
}
