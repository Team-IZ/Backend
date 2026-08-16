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

    /** 팀에 <b>배정된</b> 사람 한 명. 미배정과 같은 모양이라 화면이 한 컴포넌트로 다룰 수 있다. */
    record TeamMember(UUID teamId, UUID projectMembershipId, UUID userId, String name) {
    }

    /** 이 프로젝트 참여자 중 지금 어느 팀에도 속하지 않은 사람. */
    List<UnassignedMember> findUnassigned(UUID projectId, UUID orgId);

    /**
     * 여러 팀의 현재 구성원을 <b>한 번에</b> 읽는다(30차 R4).
     *
     * <p>팀마다 부르면 48팀짜리 목록에서 조회가 48번 나간다 — 종전 {@code countMembersByTeamIds}가
     * 그 모양이었다(주석은 "한 번에 가져온다"였는데 구현은 팀마다 count였다). 인원 수도 이 목록의
     * 길이로 세므로 세는 질의를 따로 두지 않는다.
     */
    List<TeamMember> findMembersByTeamIds(List<UUID> teamIds);

    /** projectMembershipId가 실제로 이 프로젝트·기관 소속인지. */
    boolean belongsToProject(UUID projectMembershipId, UUID projectId, UUID orgId);

    /** traineeId(user_id) → 이 프로젝트에서의 projectMembershipId. */
    Optional<UUID> findProjectMembershipId(UUID projectId, UUID orgId, UUID traineeId);
}