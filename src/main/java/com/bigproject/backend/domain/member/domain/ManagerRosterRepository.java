package com.bigproject.backend.domain.member.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 기관 매니저 목록(GET /members?role=MANAGER) 원천 조회 포트.
 *
 * <p>{@code app_user}(role=MANAGER)를 기준으로 한다. {@code MemberInvitationService.inviteManager}가
 * 초대 시점에 이미 {@code app_user} 행을 PENDING으로 만들어 두므로(operator 초대와 같은 방식),
 * 초대 대기 계정도 별도 UNION 없이 이 한 쿼리에 다 잡힌다 — {@link JdbcOrganizationOperatorRepository}와 같은 전제다.
 *
 * <p>기수·담당 반·담당 인원은 상관 서브쿼리로 채운다. JOIN + GROUP BY로 하면 페이지네이션 LIMIT/OFFSET이
 * 행 곱을 먼저 만든 뒤 자르는 꼴이 되어 페이지 경계가 깨진다.
 */
public interface ManagerRosterRepository {

	Page<ManagerRosterRow> findManagers(ManagerRosterCriteria criteria, Pageable pageable);

	record ManagerRosterCriteria(
			UUID orgId,
			String rawAccountStatus,
			String query,
			ManagerRosterSort sort
	) {
	}

	/**
	 * 매니저 한 행. {@code cohortId}·{@code cohortName}은 가장 최근 매니저 초대의 target_cohort_id다.
	 * {@code classroomNames}·{@code assignedTraineeCount}는 현재 활성 담당 배정 기준이며 담당이 없으면
	 * 각각 빈 목록·0이다. {@code lastLoginAt}은 로그인 이력이 없으면 null.
	 */
	record ManagerRosterRow(
			UUID managerId,
			String name,
			String email,
			String rawAccountStatus,
			UUID cohortId,
			String cohortName,
			List<String> classroomNames,
			long assignedTraineeCount,
			Instant lastLoginAt,
			Instant invitedAt
	) {
	}
}
