package com.pebble.api.member.application;

import com.pebble.api.auth.application.UserRefreshTokenService;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberError;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberManagementService {
    private final MemberRepository members;
    private final UserRefreshTokenService sessions;

    public Page<Member> list(MemberStatus status, String q, Pageable pageable) {
        String pattern = q == null ? null : "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Specification<Member> filter = (root, query, cb) -> {
            var state = status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
            if (pattern == null) return state;
            var term = cb.lower(cb.literal(pattern));
            return cb.and(state, cb.or(cb.like(cb.lower(root.get("nickname")), term, '\\'),
                    cb.like(cb.lower(root.get("blogName")), term, '\\'),
                    cb.like(cb.lower(root.get("handle")), term, '\\')));
        };
        return members.findAll(filter, pageable);
    }

    public Member detail(long id) {
        return members.findById(id).orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
    }

    public Member changeStatus(long id, MemberStatus status) {
        if (status == null || status == MemberStatus.WITHDRAWAL_PENDING)
            throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        Member target = members.findByIdForWrite(id)
                .orElseThrow(() -> new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND));
        if (target.getStatus() == MemberStatus.WITHDRAWAL_PENDING)
            throw new ApplicationException(MemberError.WITHDRAWAL_PENDING);
        // 회원 잠금 아래에서 폐기해 로그인·갱신이 상태 변경을 우회하지 못하게 한다.
        sessions.revokeAll(id);
        target.changeOperationalStatus(status);
        members.flush();
        return target;
    }
}
