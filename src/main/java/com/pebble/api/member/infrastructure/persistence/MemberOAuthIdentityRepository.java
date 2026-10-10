package com.pebble.api.member.infrastructure.persistence;

import com.pebble.api.member.domain.MemberOAuthIdentity;
import com.pebble.api.member.domain.OAuthProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface MemberOAuthIdentityRepository extends JpaRepository<MemberOAuthIdentity, Long> {

    @EntityGraph(attributePaths = "member")
    Optional<MemberOAuthIdentity> findByProviderAndProviderSubject(OAuthProvider provider, String providerSubject);

    Optional<MemberOAuthIdentity> findByMemberIdAndProvider(long memberId, OAuthProvider provider);

    @Query("select i.member.id from MemberOAuthIdentity i where i.provider=:provider and i.providerSubject=:subject")
    Optional<Long> findMemberId(@Param("provider") OAuthProvider provider, @Param("subject") String subject);

    @Modifying
    @Query("delete from MemberOAuthIdentity i where i.member.id=:memberId")
    int deleteForMember(@Param("memberId") long memberId);
}
