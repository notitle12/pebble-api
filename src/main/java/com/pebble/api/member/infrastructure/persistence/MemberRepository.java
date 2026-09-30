package com.pebble.api.member.infrastructure.persistence;

import com.pebble.api.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
}
