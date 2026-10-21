package com.pebble.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.member.domain.Member;
import com.pebble.api.member.domain.MemberStatus;
import com.pebble.api.member.infrastructure.persistence.MemberRepository;
import com.pebble.api.support.AuthenticationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class BoardCleanupIntegrationTest extends AuthenticationTestSupport {
    @Autowired BoardService boards;
    @Autowired MemberRepository members;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> owners = new ArrayList<>();

    @AfterEach
    void cleanup() {
        transactions.executeWithoutResult(status -> {
            for (long owner : owners) {
                jdbc.update("update board set parent_id=null where owner_member_id=?", owner);
                jdbc.update("delete from board where owner_member_id=?", owner);
                jdbc.update("delete from member where id=?", owner);
            }
        });
    }

    @Test
    void purgesDeletedDescendantBeyondActiveDepthWithoutTouchingAnotherOwner() {
        long owner = member();
        long a = create(owner, "A", null);
        long b = create(owner, "B", a);
        long c = create(owner, "C", b);
        boards.delete(c, owner);
        long d = create(owner, "D", null);
        long e = create(owner, "E", d);
        boards.update(b, owner, new BoardChanges(Set.of("parentId"), null, e, null));
        long other = member();
        long otherBoard = create(other, "other", null);

        // 활성 트리는 3단계지만 논리 삭제된 4단계 자손이 남아 있다.
        assertThat(jdbc.queryForObject("select parent_id from board where id=? and deleted_at is not null", Long.class, c))
                .isEqualTo(b);
        boards.purgeForMember(owner);

        assertThat(count(owner)).isZero();
        assertThat(jdbc.queryForObject("select id from board where owner_member_id=?", Long.class, other)).isEqualTo(otherBoard);
    }

    @Test
    void emptyAndRepeatedPurgeSucceed() {
        long owner = member();
        boards.purgeForMember(owner);
        create(owner, "root", null);
        boards.purgeForMember(owner);
        boards.purgeForMember(owner);
        assertThat(count(owner)).isZero();
    }

    @Test
    void cyclicDataStopsAndRollsBackEarlierLeafDeletion() {
        long owner = member();
        long a = create(owner, "A", null);
        long b = create(owner, "B", a);
        create(owner, "independent leaf", null);
        // 정상 API가 거부하는 손상 데이터를 직접 구성해 무한 반복 방지를 검증한다.
        jdbc.update("update board set parent_id=? where id=?", b, a);

        assertThatThrownBy(() -> boards.purgeForMember(owner))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.error().code()).isEqualTo("INTERNAL_ERROR"));
        assertThat(count(owner)).isEqualTo(3);
    }

    private long create(long owner, String name, Long parent) {
        return boards.create(owner, new BoardChanges(Set.of("name", "parentId"), name, parent, 0)).getId();
    }

    private long member() {
        long id = transactions.execute(status -> members.saveAndFlush(agreedMember(new Member(
                "cleanup-" + java.util.UUID.randomUUID().toString().substring(0, 8), null,
                MemberStatus.ACTIVE, null, null))).getId());
        owners.add(id);
        return id;
    }

    private long count(long owner) {
        return jdbc.queryForObject("select count(*) from board where owner_member_id=?", Long.class, owner);
    }
}
