package com.pebble.api.member.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberError;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Transactional
    public Member findForManagementWrite(long id) {
        // 관리자 운영은 정지·탈퇴 대기 소유자도 처리하되 기존 회원→콘텐츠 잠금 순서를 유지한다.
        return memberRepository.findByIdForWrite(id)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
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
        Member member = findActiveForAccountWrite(id);
        if (!member.hasAcceptedAgreements()) throw new ApplicationException(MemberError.AGREEMENTS_REQUIRED);
        return member;
    }

    @Transactional
    public Member findActiveForAccountWrite(long id) {
        // 동의 등록과 탈퇴는 동의 전에도 가능해야 한다.
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

    public Page<Member> searchPublicBlogs(String term, Pageable page) {
        String pattern = containsPattern(term);
        String handlePattern = term.startsWith("@") && term.length() > 1 ? containsPattern(term.substring(1)) : pattern;
        return memberRepository.findAll((root, query, cb) -> {
            var lowered = cb.lower(cb.literal(pattern));
            return cb.and(cb.notEqual(root.get("status"), MemberStatus.WITHDRAWAL_PENDING),
                    cb.isNotNull(root.get("profileCompletedAt")),
                    cb.or(cb.like(cb.lower(root.get("blogName")), lowered, '\\'),
                            cb.like(cb.lower(root.get("nickname")), lowered, '\\'),
                            cb.like(cb.lower(root.get("handle")), cb.lower(cb.literal(handlePattern)), '\\')));
        }, page);
    }

    private static String containsPattern(String term) {
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
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
