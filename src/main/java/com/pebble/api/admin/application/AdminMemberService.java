package com.pebble.api.admin.application;

import com.pebble.api.admin.domain.AdminStatus;
import com.pebble.api.admin.infrastructure.persistence.AdminAccountRepository;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.application.MemberManagementService;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminMemberService {
    private final AdminAccountRepository accounts;
    private final MemberManagementService members;

    public Page<Member> list(long actorId, MemberStatus status, String q, Pageable pageable) {
        requireOperator(actorId);
        return members.list(status, q, pageable);
    }

    public Member detail(long actorId, long memberId) {
        requireOperator(actorId);
        return members.detail(memberId);
    }

    public Member changeStatus(long actorId, long memberId, MemberStatus status) {
        requireOperator(actorId);
        return members.changeStatus(memberId, status);
    }

    private void requireOperator(long actorId) {
        // 저장 가능한 관리자 역할은 MANAGER/MASTER뿐이며 둘 다 회원 운영 권한을 가진다.
        var actor = accounts.findForAuthenticationById(actorId)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.INVALID_TOKEN));
        if (actor.getStatus() != AdminStatus.ACTIVE) throw new ApplicationException(GlobalErrorCode.INVALID_TOKEN);
    }
}
