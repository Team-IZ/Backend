package com.bigproject.backend.domain.project.infrastructure;

import com.bigproject.backend.domain.project.domain.TeamMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamMembershipRepository extends JpaRepository<TeamMembership, UUID> {

    // 지정 시점에 이 사람이 어느 팀에 있었는지.
    // 엔티티의 isEffectiveAt과 조건이 글자 그대로 같아야 한다 — 안 그러면
    // "메모리에선 유효한데 조회하면 안 나오는" 버그가 생긴다
    @Query("""
            select m from TeamMembership m
            where m.projectMembershipId = :projectMembershipId
              and m.fromAt <= :at
              and (m.toAt is null or m.toAt > :at)
            """)
    Optional<TeamMembership> findEffectiveAt(@Param("projectMembershipId") UUID projectMembershipId,
                                             @Param("at") OffsetDateTime at);

    // 팀 하나의 현재 유효 인원 조회 (toAt이 null인 것만)
    List<TeamMembership> findByTeamIdAndOrgIdAndToAtIsNull(UUID teamId, UUID orgId);
}
