package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberError;
import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MemberNicknameConcurrencyTest extends AuthenticationTestSupport {

    @Autowired
    private OAuthMemberService memberService;

    @Autowired
    private MemberOAuthIdentityRepository identityRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberProfileService profileService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> createdSubjects = new ArrayList<>();

    @AfterEach
    void removeMembersCreatedByThisTest() {
        for (String subject : createdSubjects) {
            identityRepository.findByProviderAndProviderSubject(OAuthProvider.NAVER, subject).ifPresent(identity -> {
                Long memberId = identity.getMember().getId();
                identityRepository.deleteById(identity.getId());
                memberRepository.deleteById(memberId);
            });
        }
        createdSubjects.clear();
    }

    @Test
    void concurrentDistinctNaverAccountsReceiveUniqueSuffixesWithoutOrphanMembers() throws Exception {
        String base = "parallel-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> subjects = List.of(subject(), subject(), subject());
        createdSubjects.addAll(subjects);

        try (var workers = Executors.newFixedThreadPool(subjects.size())) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Member>> results = new ArrayList<>();
            for (String identitySubject : subjects) {
                results.add(workers.submit(() -> {
                    start.await();
                    return memberService.resolve(OAuthProvider.NAVER, identitySubject, base, null);
                }));
            }
            start.countDown();
            List<Member> members = new ArrayList<>();
            for (Future<Member> result : results) {
                members.add(result.get(30, TimeUnit.SECONDS));
            }

            Set<String> nicknames = members.stream().map(Member::getNickname)
                    .collect(java.util.stream.Collectors.toSet());
            Set<Long> memberIds = members.stream().map(Member::getId)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(nicknames).containsExactlyInAnyOrder(base, base + "-2", base + "-3");
            assertThat(memberIds).hasSize(3);
            assertThat(nicknames).allMatch(nickname -> nickname.startsWith(base));
            assertThat(memberRepository.findAll().stream().filter(member -> member.getNickname().startsWith(base))
                    .map(Member::getId).collect(java.util.stream.Collectors.toSet())).isEqualTo(memberIds);
            assertThat(subjects).allSatisfy(identitySubject -> {
                var identity = identityRepository.findByProviderAndProviderSubject(OAuthProvider.NAVER, identitySubject)
                        .orElseThrow();
                assertThat(memberIds).contains(identity.getMember().getId());
            });
            assertThat(members).allSatisfy(member -> assertThat(memberRepository.existsById(member.getId())).isTrue());
        }
    }

    @Test
    void reloginWithSameSubjectPreservesPersistedNicknameAndMemberId() {
        String identitySubject = subject();
        createdSubjects.add(identitySubject);

        Member first = memberService.resolve(OAuthProvider.NAVER, identitySubject, "Original nickname", null);
        Member second = memberService.resolve(OAuthProvider.NAVER, identitySubject, "Changed nickname", null);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getNickname()).isEqualTo("Original nickname");
        assertThat(memberRepository.findById(first.getId()).orElseThrow().getNickname()).isEqualTo("Original nickname");
    }

    @Test
    void thirtyCodePointUnicodeNicknameGetsDatabaseSafeSuffix() {
        String identitySubjectOne = subject();
        String identitySubjectTwo = subject();
        createdSubjects.addAll(List.of(identitySubjectOne, identitySubjectTwo));
        String base = "가".repeat(30);

        Member first = memberService.resolve(OAuthProvider.NAVER, identitySubjectOne, base, null);
        Member second = memberService.resolve(OAuthProvider.NAVER, identitySubjectTwo, base, null);

        assertThat(first.getNickname()).isEqualTo(base);
        assertThat(second.getNickname()).isEqualTo("가".repeat(28) + "-2");
        assertThat(second.getNickname().codePointCount(0, second.getNickname().length())).isEqualTo(30);
        assertThat(memberRepository.findById(second.getId()).orElseThrow().getNickname()).isEqualTo(second.getNickname());
    }

    @Test
    void absentOrBlankNicknameUsesPebbleFallbackEvenWhenUnsuffixedNameIsTaken() {
        String absentSubject = subject();
        String blankSubject = subject();
        createdSubjects.addAll(List.of(absentSubject, blankSubject));

        Member absent = memberService.resolve(OAuthProvider.NAVER, absentSubject, null, null);
        Member blank = memberService.resolve(OAuthProvider.NAVER, blankSubject, "   ", null);

        assertThat(absent.getNickname()).startsWith("pebble");
        assertThat(blank.getNickname()).startsWith("pebble");
        assertThat(blank.getNickname()).isNotEqualTo(absent.getNickname());
    }

    @Test
    void concurrentNicknameChangesAllowOnlyOneAfterCommittedCooldownExpires() throws Exception {
        String identitySubject = subject();
        createdSubjects.add(identitySubject);
        Member member = memberService.resolve(OAuthProvider.NAVER, identitySubject, "Before change", null);
        profileService.complete(member.getId(), "blog-" + UUID.randomUUID(), "handle-" + UUID.randomUUID()
                .toString().substring(0, 8), null);
        jdbc.update("update member set nickname_changed_at = now() - interval '8 days' where id = ?", member.getId());

        List<NicknameUpdateResult> results = concurrently(() -> profileService.update(member.getId(), null,
                        "first-" + UUID.randomUUID().toString().substring(0, 8)),
                () -> profileService.update(member.getId(), null,
                        "second-" + UUID.randomUUID().toString().substring(0, 8)));

        assertThat(results.stream().filter(NicknameUpdateResult::succeeded)).hasSize(1);
        assertThat(results.stream().filter(result -> !result.succeeded()).map(NicknameUpdateResult::errorCode))
                .containsExactly(MemberError.NICKNAME_CHANGE_COOLDOWN.code());
        assertThat(memberRepository.findById(member.getId()).orElseThrow().getNickname())
                .isEqualTo(results.stream().filter(NicknameUpdateResult::succeeded).findFirst().orElseThrow().nickname());
    }

    @Test
    void concurrentProfileCompletionWithSameHandleLeavesLosingProfileIncomplete() throws Exception {
        String firstSubject = subject();
        String secondSubject = subject();
        createdSubjects.addAll(List.of(firstSubject, secondSubject));
        Member first = memberService.resolve(OAuthProvider.NAVER, firstSubject, "First", null);
        Member second = memberService.resolve(OAuthProvider.NAVER, secondSubject, "Second", null);
        String handle = "shared-" + UUID.randomUUID().toString().substring(0, 8);

        List<ProfileCompletionResult> results = concurrently(first.getId(),
                () -> profileService.complete(first.getId(), "blog-" + UUID.randomUUID(), handle, null),
                second.getId(),
                () -> profileService.complete(second.getId(), "blog-" + UUID.randomUUID(), handle, null));

        assertThat(results.stream().filter(ProfileCompletionResult::succeeded)).hasSize(1);
        assertThat(results.stream().filter(result -> !result.succeeded()).map(ProfileCompletionResult::errorCode))
                .containsExactly(MemberError.DUPLICATE_HANDLE.code());
        Long losingMemberId = results.stream().filter(result -> !result.succeeded()).findFirst().orElseThrow().memberId();
        assertThat(memberRepository.findById(losingMemberId).orElseThrow().isProfileCompleted()).isFalse();
    }

    private List<NicknameUpdateResult> concurrently(UpdateAction first, UpdateAction second) throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<NicknameUpdateResult> firstResult = workers.submit(() -> {
                start.await();
                try {
                    Member member = first.run();
                    return new NicknameUpdateResult(true, member.getId(), member.getNickname(), null);
                } catch (ApplicationException exception) {
                    return new NicknameUpdateResult(false, null, null, exception.error().code());
                }
            });
            Future<NicknameUpdateResult> secondResult = workers.submit(() -> {
                start.await();
                try {
                    Member member = second.run();
                    return new NicknameUpdateResult(true, member.getId(), member.getNickname(), null);
                } catch (ApplicationException exception) {
                    return new NicknameUpdateResult(false, null, null, exception.error().code());
                }
            });
            start.countDown();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        }
    }

    private List<ProfileCompletionResult> concurrently(long firstMemberId, ProfileAction first, long secondMemberId,
                                                       ProfileAction second) throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<ProfileCompletionResult> firstResult = workers.submit(() -> completeAfterStart(start, firstMemberId, first));
            Future<ProfileCompletionResult> secondResult = workers.submit(() -> completeAfterStart(start, secondMemberId, second));
            start.countDown();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        }
    }

    private ProfileCompletionResult completeAfterStart(CountDownLatch start, long memberId, ProfileAction action) throws Exception {
        start.await();
        try {
            Member member = action.run();
            return new ProfileCompletionResult(true, member.getId(), null);
        } catch (ApplicationException exception) {
            return new ProfileCompletionResult(false, memberId, exception.error().code());
        }
    }

    private String subject() {
        return "nickname-test-" + UUID.randomUUID();
    }

    @FunctionalInterface
    private interface UpdateAction {
        Member run();
    }

    @FunctionalInterface
    private interface ProfileAction {
        Member run();
    }

    private record NicknameUpdateResult(boolean succeeded, Long memberId, String nickname, String errorCode) {
    }

    private record ProfileCompletionResult(boolean succeeded, Long memberId, String errorCode) {
    }
}
