package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OAuthMemberServiceTest {

    @Test
    void unconfirmedProviderCannotCreateAnAccount() {
        var members = mock(MemberRepository.class);
        var identities = mock(MemberOAuthIdentityRepository.class);
        var transactions = mock(PlatformTransactionManager.class);
        var names = mock(MemberNames.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(identities.findByProviderAndProviderSubject(OAuthProvider.NAVER, "new-subject"))
                .thenReturn(Optional.empty());
        var service = new OAuthMemberService(members, identities, transactions, names);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolve(OAuthProvider.NAVER,
                "new-subject", "name", null, false))
                .isInstanceOf(com.pebble.api.global.exception.ApplicationException.class)
                .hasMessageContaining("SNS");
        verify(members, never()).saveAndFlush(any());
        verify(identities, never()).saveAndFlush(any());
        verify(names, never()).lockNames();
    }

    @Test
    void existingManuallyRegisteredProfileImageIsNotReplacedByOAuthProfile() {
        var members = mock(MemberRepository.class);
        var identities = mock(MemberOAuthIdentityRepository.class);
        var transactions = mock(PlatformTransactionManager.class);
        var names = mock(MemberNames.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var member = new Member("manual-name", "https://images.example/manual.webp", MemberStatus.ACTIVE, null, null);
        when(identities.findByProviderAndProviderSubject(OAuthProvider.NAVER, "subject"))
                .thenReturn(Optional.of(new MemberOAuthIdentity(member, OAuthProvider.NAVER, "subject")));
        var service = new OAuthMemberService(members, identities, transactions, names);

        var resolved = service.resolve(OAuthProvider.NAVER, "subject", "new-nickname",
                "https://nid.naver.com/profile_image?new-photo=true", false);

        assertThat(resolved).isSameAs(member);
        assertThat(resolved.getProfileImageUrl()).isEqualTo("https://images.example/manual.webp");
        verify(members, never()).saveAndFlush(any());
        verify(names, never()).lockNames();
    }
}
