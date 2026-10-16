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
        Member member = queries.findActiveForAccountWrite(memberId);
        Instant deletedAt = clock.instant();
        // 회원 잠금 안에서 콘텐츠·계정을 함께 파기한다. 실패 시 DB 전체를 롤백한다.
        purge(member);
        return deletedAt;
    }

    public void confirmWithdrawal(long memberId, String confirmation) {
        Member member = queries.findActiveForAccountWrite(memberId);
        String expected = member.getBlogName() == null || member.getBlogName().isBlank()
                ? "내 계정을 삭제하는데 동의합니다." : "블로그를 삭제하는데 동의합니다.";
        if (!expected.equals(confirmation)) {
            throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, "confirmation", "안내된 탈퇴 확인 문구를 정확히 입력해 주세요.");
        }
        purge(member);
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
        purge(member);
        return true;
    }

    private void purge(Member member) {
        long memberId = member.getId();
        sessions.revokeAll(memberId);
        posts.purgeForMember(memberId);
        projects.purgeForMember(memberId);
        boards.purgeForMember(memberId);
        identities.deleteForMember(memberId);
        // 작성자가 다른 대상에 남긴 댓글·좋아요도 회원 FK CASCADE가 제거한다.
        // DB의 CASCADE·파일 회수 트리거를 실행하고, 벌크 파기 후 남은 관리 객체를 비운다.
        members.deleteForWithdrawal(memberId);
    }

    private static ApplicationException notFound() { return new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND); }
}
