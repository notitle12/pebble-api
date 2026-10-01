package com.pebble.api.member.application;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberError;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberProfileService {
    public static final Duration CHANGE_COOLDOWN = Duration.ofDays(7);
    private final MemberQueryService memberQueries;
    private final MemberRepository members;
    private final MemberNames names;

    public Member complete(long memberId, String blogName, String handle, String nickname) {
        Member member = memberQueries.findActiveForWrite(memberId);
        if (member.isProfileCompleted()) throw new ApplicationException(MemberError.PROFILE_ALREADY_COMPLETED);
        names.lockNames();
        String chosen = nickname == null ? member.getNickname() : nickname;
        checkNickname(member, chosen);
        if (members.existsByBlogName(blogName)) throw new ApplicationException(MemberError.DUPLICATE_BLOG_NAME);
        if (members.existsByHandle(handle)) throw new ApplicationException(MemberError.DUPLICATE_HANDLE);
        member.completeProfile(blogName, handle, chosen, Instant.now());
        members.flush();
        return member;
    }

    public Member update(long memberId, String blogName, String nickname) {
        Member member = memberQueries.findActiveForWrite(memberId);
        if (!member.isProfileCompleted()) throw new ApplicationException(MemberError.PROFILE_REQUIRED);
        names.lockNames();
        Instant now = Instant.now();
        boolean nicknameChange = nickname != null && !nickname.equals(member.getNickname());
        boolean blogChange = blogName != null && !blogName.equals(member.getBlogName());
        // 모든 필드의 조건을 확인한 뒤 변경하여 부분 성공을 만들지 않는다.
        if (nicknameChange && now.isBefore(member.getNicknameChangedAt().plus(CHANGE_COOLDOWN))) {
            throw new ApplicationException(MemberError.NICKNAME_CHANGE_COOLDOWN);
        }
        if (blogChange && now.isBefore(member.getBlogNameChangedAt().plus(CHANGE_COOLDOWN))) {
            throw new ApplicationException(MemberError.BLOG_NAME_CHANGE_COOLDOWN);
        }
        if (nicknameChange) checkNickname(member, nickname);
        if (blogChange && members.existsByBlogName(blogName)) throw new ApplicationException(MemberError.DUPLICATE_BLOG_NAME);
        if (nicknameChange) member.changeNickname(nickname, now);
        if (blogChange) member.changeBlogName(blogName, now);
        members.flush();
        return member;
    }

    private void checkNickname(Member member, String chosen) {
        if (!chosen.equals(member.getNickname()) && members.existsByNickname(chosen)) {
            throw new ApplicationException(MemberError.DUPLICATE_NICKNAME);
        }
    }
}
