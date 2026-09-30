package com.pebble.api.member.infrastructure.persistence;

import com.pebble.api.member.domain.MemberOAuthIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberOAuthIdentityRepository extends JpaRepository<MemberOAuthIdentity, Long> {
}
