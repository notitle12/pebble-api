package com.pebble.api.member.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.domain.OAuthProvider;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MemberPersistenceTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberOAuthIdentityRepository identityRepository;

    @Test
    void persistsMemberAndOAuthIdentityWithGeneratedIds() {
        Member member = memberRepository.saveAndFlush(
                new Member("pebble", null, MemberStatus.ACTIVE, null, null));
        MemberOAuthIdentity identity = identityRepository.saveAndFlush(
                new MemberOAuthIdentity(member, OAuthProvider.NAVER, "naver-subject-1"));

        assertThat(member.getId()).isPositive();
        assertThat(member.getCreatedAt()).isNotNull();
        assertThat(member.getUpdatedAt()).isNotNull();
        assertThat(identity.getId()).isPositive();
        assertThat(identity.getCreatedAt()).isNotNull();
        assertThat(identityRepository.findById(identity.getId()))
                .get()
                .extracting(MemberOAuthIdentity::getProviderSubject)
                .isEqualTo("naver-subject-1");
    }

    @Test
    void rejectsDuplicateProviderSubject() {
        Member first = saveMember("first");
        Member second = saveMember("second");
        identityRepository.saveAndFlush(identity(first, "same-subject"));

        assertThatThrownBy(() -> identityRepository.saveAndFlush(identity(second, "same-subject")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMoreThanOneIdentityForMemberAndProvider() {
        Member member = saveMember("pebble");
        identityRepository.saveAndFlush(identity(member, "subject-1"));

        assertThatThrownBy(() -> identityRepository.saveAndFlush(identity(member, "subject-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsWithdrawalPendingMemberWithoutBothTimestamps() {
        Member member = new Member("pebble", null, MemberStatus.WITHDRAWAL_PENDING,
                Instant.now(), null);

        assertThatThrownBy(() -> memberRepository.saveAndFlush(member))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Member saveMember(String nickname) {
        return memberRepository.saveAndFlush(
                new Member(nickname, null, MemberStatus.ACTIVE, null, null));
    }

    private MemberOAuthIdentity identity(Member member, String subject) {
        return new MemberOAuthIdentity(member, OAuthProvider.NAVER, subject);
    }
}
