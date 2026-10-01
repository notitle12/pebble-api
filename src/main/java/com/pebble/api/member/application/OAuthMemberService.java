package com.pebble.api.member.application;

import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import java.util.Objects;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OAuthMemberService {

    private final MemberRepository memberRepository;
    private final MemberOAuthIdentityRepository identityRepository;
    private final TransactionTemplate transaction;

    public OAuthMemberService(MemberRepository memberRepository,
                              MemberOAuthIdentityRepository identityRepository,
                              PlatformTransactionManager transactionManager) {
        this.memberRepository = memberRepository;
        this.identityRepository = identityRepository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Member resolve(OAuthProvider provider, String subject, String nickname, String profileImageUrl) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> identityRepository
                    .findByProviderAndProviderSubject(provider, subject)
                    .map(MemberOAuthIdentity::getMember)
                    .orElseGet(() -> createMember(provider, subject, nickname, profileImageUrl))));
        } catch (DataIntegrityViolationException exception) {
            if (!isConcurrentRegistration(exception)) {
                throw exception;
            }
            // 동일 OAuth 계정의 동시 가입에서 먼저 저장된 회원을 다시 조회한다.
            return Objects.requireNonNull(transaction.execute(status -> identityRepository
                    .findByProviderAndProviderSubject(provider, subject)
                    .map(MemberOAuthIdentity::getMember)
                    .orElseThrow(() -> exception)));
        }
    }

    public java.util.Optional<MemberStatus> findStatus(long memberId) {
        return transaction.execute(status -> memberRepository.findById(memberId).map(Member::getStatus));
    }

    private Member createMember(OAuthProvider provider, String subject, String nickname, String profileImageUrl) {
        String initialNickname = nickname == null || nickname.isBlank() ? "pebble" : nickname;
        Member member = memberRepository.saveAndFlush(
                new Member(initialNickname, profileImageUrl, MemberStatus.ACTIVE, null, null));
        identityRepository.saveAndFlush(new MemberOAuthIdentity(member, provider, subject));
        return member;
    }

    private boolean isConcurrentRegistration(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "uk_member_oauth_identity_provider_subject".equals(violation.getConstraintName());
            }
        }
        return false;
    }
}
