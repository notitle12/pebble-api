package com.pebble.api.member.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberError;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberQueryService {

    private final MemberRepository memberRepository;

    @Transactional
    public java.util.Optional<Member> findForAuthentication(long id) {
        return memberRepository.findForAuthentication(id);
    }

    public Member findActiveById(long id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));

        return requireActive(member);
    }

    public boolean canReadPrivateInteractions(long id) {
        return memberRepository.findById(id).map(member -> member.getStatus() == MemberStatus.ACTIVE).orElse(false);
    }

    @Transactional
    public Member findActiveForWrite(long id) {
        return requireActive(memberRepository.findByIdForWrite(id)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND)));
    }

    public Member findProfileCompleted(long id) {
        Member member = findActiveById(id);
        if (!member.isProfileCompleted()) throw new ApplicationException(MemberError.PROFILE_REQUIRED);
        return member;
    }

    public Member findPublicById(long id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
        if (member.getStatus() == MemberStatus.WITHDRAWAL_PENDING) {
            throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        }
        return member;
    }

    public Member findPublicBlog(String handle) {
        Member member = memberRepository.findByHandle(handle)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
        if (member.getStatus() == MemberStatus.WITHDRAWAL_PENDING || !member.isProfileCompleted()) {
            throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        }
        return member;
    }

    @Transactional
    public Member findProfileCompletedForWrite(long id) {
        Member member = findActiveForWrite(id);
        if (!member.isProfileCompleted()) throw new ApplicationException(MemberError.PROFILE_REQUIRED);
        return member;
    }

    private Member requireActive(Member member) {
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new ApplicationException(MemberError.ACCOUNT_SUSPENDED);
        }
        if (member.getStatus() == MemberStatus.WITHDRAWAL_PENDING) {
            throw new ApplicationException(MemberError.ACCOUNT_WITHDRAWAL_PENDING);
        }
        return member;
    }
}
