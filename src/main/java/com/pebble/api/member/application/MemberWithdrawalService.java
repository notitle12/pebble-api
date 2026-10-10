package com.pebble.api.member.application;

import com.pebble.api.auth.application.UserRefreshTokenService;
import com.pebble.api.board.application.BoardService;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.post.application.PostService;
import com.pebble.api.project.application.ProjectService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberWithdrawalService {
    private final MemberRepository members;
    private final MemberQueryService queries;
    private final MemberOAuthIdentityRepository identities;
    private final UserRefreshTokenService sessions;
    private final PostService posts;
    private final ProjectService projects;
    private final BoardService boards;
    private final Clock clock;

    public Instant request(long memberId) {
        Member member = queries.findActiveForWrite(memberId);
        sessions.revokeAll(memberId);
        member.requestWithdrawal(clock.instant());
        members.flush();
        return member.getWithdrawalScheduledAt();
    }

    public String subjectForWithdrawal(long memberId, OAuthProvider provider) {
        queries.findActiveById(memberId);
        return identities.findByMemberIdAndProvider(memberId, provider)
                .orElseThrow(MemberWithdrawalService::notFound).getProviderSubject();
    }

    public void restoreOnLogin(long memberId) {
        Member member = members.findByIdForWrite(memberId).orElseThrow(MemberWithdrawalService::notFound);
        if (member.getStatus() == MemberStatus.WITHDRAWAL_PENDING) {
            member.cancelWithdrawal(clock.instant());
            sessions.revokeAll(memberId);
            members.flush();
        }
    }

    public void cancel(OAuthProvider provider, String subject) {
        // 잠금 대기 전에 회원 Entity를 로드하지 않아 최신 예약 상태를 확인한다.
        long memberId = identities.findMemberId(provider, subject).orElseThrow(MemberWithdrawalService::notFound);
        Member member = members.findByIdForWrite(memberId).orElseThrow(MemberWithdrawalService::notFound);
        member.cancelWithdrawal(clock.instant());
        sessions.revokeAll(memberId);
        members.flush();
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return members.findExpiredWithdrawalIds(clock.instant(), PageRequest.of(0, 100));
    }

    public boolean deleteExpired(long memberId) {
        var locked = members.findForWithdrawalCleanup(memberId);
        if (locked.isEmpty()) return false;
        Member member = locked.get();
        if (member.getStatus() != MemberStatus.WITHDRAWAL_PENDING || clock.instant().isBefore(member.getWithdrawalScheduledAt())) return false;
        sessions.revokeAll(memberId);
        posts.purgeForMember(memberId);
        projects.purgeForMember(memberId);
        boards.purgeForMember(memberId);
        identities.deleteForMember(memberId);
        // 작성자가 다른 대상에 남긴 댓글·좋아요도 회원 FK CASCADE가 제거한다.
        members.delete(member);
        members.flush();
        return true;
    }

    private static ApplicationException notFound() { return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND); }
}
