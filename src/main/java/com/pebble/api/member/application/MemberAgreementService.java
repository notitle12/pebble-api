package com.pebble.api.member.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.domain.MemberAgreement;
import com.pebble.api.member.domain.MemberError;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MemberAgreementService {
    private final MemberQueryService queries;
    private final MemberRepository members;
    private final Clock clock;
    private final boolean published;
    public MemberAgreementService(MemberQueryService queries, MemberRepository members, Clock clock,
            @Value("${pebble.legal.published:false}") boolean published) {
        this.queries = queries; this.members = members; this.clock = clock; this.published = published;
    }
    public State state(long memberId) {
        return state(queries.findActiveById(memberId).currentAgreementAcceptedAt().orElse(null));
    }
    @Transactional
    public State accept(long memberId, String termsVersion, String privacyVersion) {
        var member = queries.findActiveForAccountWrite(memberId);
        if (!MemberAgreement.TERMS_VERSION.equals(termsVersion) || !MemberAgreement.PRIVACY_VERSION.equals(privacyVersion)) {
            throw new ApplicationException(MemberError.AGREEMENT_VERSION_CHANGED);
        }
        // 이미 처리한 동일 요청은 원래 서버 동의 시각을 반환한다.
        if (member.hasAcceptedAgreements()) return state(member.currentAgreementAcceptedAt().orElseThrow());
        if (!published) throw new ApplicationException(MemberError.LEGAL_DOCUMENTS_UNAVAILABLE);
        // PostgreSQL timestamptz 정밀도에 맞춰 최초 응답과 재조회 응답을 동일하게 유지한다.
        member.acceptCurrentAgreements(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        members.flush();
        return state(member.currentAgreementAcceptedAt().orElseThrow());
    }
    private State state(Instant at) {
        return new State(MemberAgreement.TERMS_VERSION, MemberAgreement.PRIVACY_VERSION, at != null, at, published);
    }
    public record State(String termsVersion, String privacyVersion, boolean accepted, Instant acceptedAt, boolean available) {}
}
