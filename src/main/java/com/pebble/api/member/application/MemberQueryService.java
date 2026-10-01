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

    public Member findActiveById(long id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));

        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new ApplicationException(MemberError.ACCOUNT_SUSPENDED);
        }
        if (member.getStatus() == MemberStatus.WITHDRAWAL_PENDING) {
            throw new ApplicationException(MemberError.ACCOUNT_WITHDRAWAL_PENDING);
        }
        return member;
    }
}
