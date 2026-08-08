package com.bigproject.backend.domain.member.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 기관 매니저 목록(GET /managers) 원천 조회 포트.
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

	/**
	 * 매니저 한 명. 상태 변경·초대 취소·재발송 응답이 <b>바뀐 그 행</b>을 그대로 돌려주는 데 쓴다(9차 R7).
	 * 목록과 같은 SELECT를 쓰므로 두 응답의 필드가 어긋날 수 없다.
	 *
	 * <p>기수 범위를 걸지 않는다 — 계정 조작은 기관 단위이고, 조작 대상은 이미 ID로 특정돼 있다.
	 */
	java.util.Optional<ManagerRosterRow> findManager(UUID orgId, UUID managerId);

	/**
	 * 기관 전체의 활성(ACTIVE) 매니저 수. {@code suspendable} 판정에 쓴다 —
	 * 마지막 활성 매니저를 정지하면 그 기관의 담당이 통째로 빈다.
	 */
	int countActiveManagers(UUID orgId);

	/**
	 * 계정 상태별 매니저 수. <b>상태·검색 필터를 적용하지 않은</b> 모집단이라 페이지의
	 * {@code totalElements}와 다르다. 화면 상단이 '매니저 8명 · 활성 7 · 초대 대기 1 · 정지 0'을
	 * 필터와 무관하게 보여주는데, 그 내역을 목록 한 페이지에서는 만들 수 없어 따로 센다.
	 *
	 * <p>다만 {@code cohortId}는 필터가 아니라 <b>모집단 자체</b>라 목록과 똑같이 적용한다. 화면의
	 * 상태 칩은 그 기수 매니저를 세는 값이고, 여기만 기관 전체로 세면 칩 합계가 목록 건수와 어긋난다.
	 *
	 * @param cohortId 담당 기수로 좁힌다. null이면 기관 전체
	 * @return {@code app_user.status} 원문(PENDING·ACTIVE·INACTIVE)별 인원. 0인 상태는 키가 없다
	 */
	Map<String, Long> countByStatus(UUID orgId, UUID cohortId);

	/**
	 * @param cohortId 담당 기수로 좁힌다. null이면 기관 전체를 조회한다.
	 *                 매니저가 그 기수에 속하는 기준은 <b>담당 반 배정 또는 매니저 초대</b> 둘 중 하나이며,
	 *                 초대만 되고 아직 반이 없는 매니저(화면의 '미배정')도 그 기수 목록에 나와야 하므로 OR다
	 */
	record ManagerRosterCriteria(
			UUID orgId,
			UUID cohortId,
			String rawAccountStatus,
			String query,
			ManagerRosterSort sort
	) {
	}

	/**
	 * 매니저 한 행. {@code cohortId}·{@code cohortName}은 가장 최근 매니저 초대의 target_cohort_id다.
	 * {@code classroomNames}·{@code assignedTraineeCount}는 현재 활성 담당 배정 기준이며 담당이 없으면
	 * 각각 빈 목록·0이다. {@code lastLoginAt}은 로그인 이력이 없으면 null.
	 *
	 * <p>{@code invitedAt}·{@code invitedByName}은 <b>같은 초대 행</b>(가장 이른 매니저 초대)에서 함께
	 * 읽는다. 화면 비고가 '2026-07-24 초대 · 김오퍼레이터'처럼 날짜와 사람을 한 문장으로 붙여 쓰는데,
	 * 둘을 다른 행에서 가져오면 재초대가 있었을 때 A가 초대한 날짜에 B의 이름이 붙는다.
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
			Instant invitedAt,
			String invitedByName,
			/**
			 * 아직 수락·취소되지 않은 초대 토큰. 재발송·취소가 <b>토큰 단위</b>라 목록에 이 값이 없으면
			 * 화면이 버튼을 켤 수도, 어느 토큰을 지목할지도 알 수 없다(9차 R7). null이면 버튼을 잠근다.
			 */
			UUID pendingInvitationTokenId
	) {
	}
}
