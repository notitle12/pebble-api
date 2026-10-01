package com.pebble.api.member.infrastructure.persistence;

import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.OAuthProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberOAuthIdentityRepository extends JpaRepository<MemberOAuthIdentity, Long> {

    @EntityGraph(attributePaths = "member")
    Optional<MemberOAuthIdentity> findByProviderAndProviderSubject(OAuthProvider provider, String providerSubject);
}
