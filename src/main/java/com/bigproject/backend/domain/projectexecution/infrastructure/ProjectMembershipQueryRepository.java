package com.bigproject.backend.domain.projectexecution.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * project_membership 조회 전용 포트. 이 테이블은 JPA 엔티티가 없다 — Analytics 도메인도
 * 같은 테이블을 순수 SQL로만 읽는다(JdbcRiskTraineeQueryRepository 참고).
 * team_membership.project_membership_id가 이 테이블의 PK를 가리키지만 FK 매핑은 없다.
 */
public interface ProjectMembershipQueryRepository {

    /** {@code classId}는 담당 반이 여럿인 매니저의 화면이 사람을 반별로 묶는 데 쓴다. */
    record UnassignedMember(UUID projectMembershipId, UUID userId, String name, UUID classId) {
    }

    /** 팀에 <b>배정된</b> 사람 한 명. 미배정과 같은 모양이라 화면이 한 컴포넌트로 다룰 수 있다. */
    record TeamMember(UUID teamId, UUID projectMembershipId, UUID userId, String name) {
    }

    /**
     * 이 프로젝트 참여자 중 지금 어느 팀에도 속하지 않은 사람. <b>반으로 좁혀서</b> 읽는다.
     *
     * <p>🔴 {@code classIds}가 없으면 안 된다. 종전에는 (projectId, orgId)만 받아 <b>기수 전원</b>을
     * 돌려줬고, 그 목록이 곧 팀 목록 화면의 미배정 배너이자 자동 배분의 대상이었다. 팀은 반별로
     * 짜는데 대상은 기수 전체라, 자동 배분 한 번이 <b>다른 반 교육생을 내 반 팀에 넣었다</b>
     * (7기 249명 → 한 반에 63팀). 증상이 늦게 드러난 것은 신규 프로젝트의 project_membership이
     * 0건이라 목록이 늘 비어 있었기 때문이다.
     *
     * <p>빈 목록을 넘기면 빈 결과다 — 담당 반이 없는 매니저에게 기수 전원을 보여주는 것보다
     * 아무것도 안 보여주는 쪽이 맞다.
     */
    List<UnassignedMember> findUnassigned(UUID projectId, UUID orgId, Collection<UUID> classIds);

    /**
     * 여러 팀의 현재 구성원을 <b>한 번에</b> 읽는다(30차 R4).
     *
     * <p>팀마다 부르면 48팀짜리 목록에서 조회가 48번 나간다 — 종전 {@code countMembersByTeamIds}가
     * 그 모양이었다(주석은 "한 번에 가져온다"였는데 구현은 팀마다 count였다). 인원 수도 이 목록의
     * 길이로 세므로 세는 질의를 따로 두지 않는다.
     */
    List<TeamMember> findMembersByTeamIds(List<UUID> teamIds);

    /**
     * projectMembershipId가 실제로 이 프로젝트·기관, 그리고 <b>이 반</b> 소속인지.
     *
     * <p>반까지 보는 이유: 팀은 반에 속하는데 종전 검사는 프로젝트 소속만 봐서
     * <b>다른 반 교육생을 내 반 팀에 넣을 수 있었다.</b> 팀 목록·자동 배분을 반으로 좁혀도
     * 이 자리가 열려 있으면 수동 배정으로 같은 상태를 만들 수 있다.
     */
    boolean belongsToProjectAndClass(UUID projectMembershipId, UUID projectId, UUID orgId, UUID classId);

    /** traineeId(user_id) → 이 프로젝트에서의 projectMembershipId. */
    Optional<UUID> findProjectMembershipId(UUID projectId, UUID orgId, UUID traineeId);
}