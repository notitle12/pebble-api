package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.domain.*;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MemberAgreementServiceTest {
    @Test void unpublishedDocumentsCannotBeAcceptedAndNeverPersistAReceipt() {
        var queries = mock(MemberQueryService.class);
        var repository = mock(MemberRepository.class);
        var pending = new Member("회원", null, MemberStatus.ACTIVE, null, null);
        when(queries.findActiveForAccountWrite(1)).thenReturn(pending);
        var service = new MemberAgreementService(queries, repository, Clock.systemUTC(), false, false);
        assertThatThrownBy(() -> service.accept(1, MemberAgreement.TERMS_VERSION, MemberAgreement.PRIVACY_VERSION))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.error()).isEqualTo(MemberError.LEGAL_DOCUMENTS_UNAVAILABLE));
        assertThat(pending.hasAcceptedAgreements()).isFalse();
        verify(repository, never()).flush();
    }
    @Test void acceptsOnlyTheServerDocumentVersionAndRecordsServerTime() {
        var queries = mock(MemberQueryService.class);
        var repository = mock(MemberRepository.class);
        var pending = new Member("회원", null, MemberStatus.ACTIVE, null, null);
        when(queries.findActiveForAccountWrite(1)).thenReturn(pending);
        Instant now = Instant.parse("2026-10-15T03:00:00.123456789Z");
        var service = new MemberAgreementService(queries, repository, Clock.fixed(now, ZoneOffset.UTC), true, false);
        assertThatThrownBy(() -> service.accept(1, "old", MemberAgreement.PRIVACY_VERSION))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.error()).isEqualTo(MemberError.AGREEMENT_VERSION_CHANGED));
        var result = service.accept(1, MemberAgreement.TERMS_VERSION, MemberAgreement.PRIVACY_VERSION);
        assertThat(result.acceptedAt()).isEqualTo(Instant.parse("2026-10-15T03:00:00.123456Z"));
        assertThat(result.accepted()).isTrue();
    }
    @Test void disablingNewEnrollmentDoesNotEraseAnExistingAcceptance() {
        var queries = mock(MemberQueryService.class);
        var repository = mock(MemberRepository.class);
        var member = new Member("회원", null, MemberStatus.ACTIVE, null, null);
        Instant now = Instant.parse("2026-10-15T03:00:00Z");
        member.acceptCurrentAgreements(now);
        when(queries.findActiveForAccountWrite(1)).thenReturn(member);
        var result = new MemberAgreementService(queries, repository, Clock.systemUTC(), false, false)
                .accept(1, MemberAgreement.TERMS_VERSION, MemberAgreement.PRIVACY_VERSION);
        assertThat(result.acceptedAt()).isEqualTo(now);
        assertThat(result.available()).isFalse();
        verify(repository, never()).flush();
    }
}
