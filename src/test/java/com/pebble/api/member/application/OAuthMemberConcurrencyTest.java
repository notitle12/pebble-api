package com.pebble.api.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pebble.api.member.domain.OAuthProvider;
import com.pebble.api.member.infrastructure.persistence.MemberOAuthIdentityRepository;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OAuthMemberConcurrencyTest extends AuthenticationTestSupport {

    @Autowired
    private OAuthMemberService memberService;

    @Autowired
    private MemberOAuthIdentityRepository identityRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void concurrentRegistrationReturnsTheSameMemberWithoutOrphanAccounts() throws Exception {
        String subject = "concurrent-" + UUID.randomUUID();
        long initialCount = memberRepository.count();
        try (var workers = Executors.newFixedThreadPool(4)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(workers.submit(() -> {
                    start.await();
                    return memberService.resolve(OAuthProvider.NAVER, subject, "pebble", null).getId();
                }));
            }
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Long> result : results) {
                ids.add(result.get(20, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(4).containsOnly(ids.getFirst());
            assertThat(memberRepository.count()).isEqualTo(initialCount + 1);
        } finally {
            identityRepository.findByProviderAndProviderSubject(OAuthProvider.NAVER, subject).ifPresent(identity -> {
                Long memberId = identity.getMember().getId();
                identityRepository.deleteById(identity.getId());
                memberRepository.deleteById(memberId);
            });
        }
    }
}
