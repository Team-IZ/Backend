package com.bigproject.backend.domain.projectexecution.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * project_membership 조회 전용 포트. 이 테이블은 JPA 엔티티가 없다 — Analytics 도메인도
 * 같은 테이블을 순수 SQL로만 읽는다(JdbcRiskTraineeQueryRepository 참고).
 * team_membership.project_membership_id가 이 테이블의 PK를 가리키지만 FK 매핑은 없다.
 */
public interface ProjectMembershipQueryRepository {

    record UnassignedMember(UUID projectMembershipId, UUID userId, String name) {
    }

    /** 이 프로젝트 참여자 중 지금 어느 팀에도 속하지 않은 사람. */
    List<UnassignedMember> findUnassigned(UUID projectId, UUID orgId);

    /** projectMembershipId가 실제로 이 프로젝트·기관 소속인지. */
    boolean belongsToProject(UUID projectMembershipId, UUID projectId, UUID orgId);

    /** traineeId(user_id) → 이 프로젝트에서의 projectMembershipId. */
    Optional<UUID> findProjectMembershipId(UUID projectId, UUID orgId, UUID traineeId);
}