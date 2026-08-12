package com.bigproject.backend.domain.member.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 기수 교육생 명단(GET /cohorts/{cohortId}/trainees) 원천 조회·상태 변경 포트.
 *
 * <p>수락된 교육생은 {@code cohort_member}, 초대 대기는 {@code user_invitation}에서 읽은 뒤
 * {@code class_membership}·{@code class}·{@code app_user}와 합친다.
 * academicoperations 도메인의 JPA 엔티티를 재사용하지 않는 이유는 이름·이메일·계정 상태가 {@code app_user}에
 * 있고 그건 auth 도메인 소유라, 이 화면 하나를 위해 도메인 간 JPA 연관을 만들기보다 이 화면이 필요한 필드만
 * 직접 SQL로 뽑는 편(이미 organization·analytics 도메인이 쓰는 방식)이 더 적다.
 */
public interface TraineeRosterRepository {

	Optional<CohortScope> findCohortScope(UUID cohortId);

	Page<RosterRow> findRoster(RosterCriteria criteria, Pageable pageable);

	/**
	 * 반 배정이 없는 교육생 수. 초대 대기도 아직 배정되지 않은 명단 인원으로 포함한다.
	 *
	 * @param scopedManagerId 담당 반으로 좁힐 매니저. 오퍼레이터는 null이며, 매니저면 항상 0을 돌려준다 —
	 *                        배정되지 않은 교육생은 어느 매니저의 담당도 아니라 목록에 들어오지 않는다.
	 */
	int countUnassigned(UUID cohortId, UUID orgId, UUID scopedManagerId);

	/**
	 * 화면 상단 총원. <b>필터를 적용하지 않은</b> 모집단이라 페이지의 {@code totalElements}와 다르다.
	 * 화면이 '명단 393명'과 '7기 393명에서 찾았습니다'를 필터와 무관하게 보여주는데, 그 값을
	 * 목록 한 페이지에서는 만들 수 없어 따로 센다.
	 *
	 * @param scopedManagerId 담당 반으로 좁힐 매니저. 오퍼레이터는 null(기수 전체)이다.
	 *                        <b>목록과 같은 모집단이어야 한다</b> — 매니저 화면에 담당 반 21명이 떠 있는데
	 *                        총원이 393명이면 두 숫자가 서로 다른 것을 센다.
	 */
	int countCohortTotal(UUID cohortId, UUID orgId, UUID scopedManagerId);

	Optional<RosterRow> findTrainee(UUID traineeId, UUID cohortId, UUID orgId);

	/** @return 실제로 바뀐 행 수(0이면 대상 없음 또는 이미 같은 상태) */
	int updateStatus(UUID traineeId, String rawStatus, UUID actorUserId, String reasonCode, String reason);

	/**
	 * 기수 소속(cohort_member)의 이탈 여부를 계정 상태에 맞춘다.
	 *
	 * <p>테이블정의서는 {@code AppUser.status}와 {@code CohortMember.status}를 서로 다른 생명주기로
	 * 규정하지만, 화면은 한 행에 '계정 비활성'과 '중도 이탈 {날짜}'를 함께 보여주므로 상태 변경 API가
	 * 둘을 함께 움직인다.
	 *
	 * <p>{@code left}가 true면 {@code status='LEFT'}와 {@code left_at}을 함께 채우고, false면 둘 다 되돌린다.
	 * COMMENT의 불변식이 <b>ACTIVE면 left_at IS NULL, LEFT면 left_at 필수</b>라 한쪽만 바꾸면 안 된다 —
	 * DB CHECK가 없어 어긋나도 저장은 되기 때문에 여기서 지켜야 한다.
	 *
	 * @return 실제로 바뀐 행 수
	 */
	int updateCohortMembership(UUID traineeId, UUID cohortId, UUID orgId, boolean left);

	record CohortScope(UUID cohortId, UUID orgId) {
	}

	/**
	 * @param managerId       회차 지표(위험·우수)를 붙일 매니저. 지표는 매니저·회차 단위 집계라
	 *                        이 값이 없으면 해당 컬럼이 전부 null이다.
	 * @param scopedManagerId <b>목록 자체를 담당 반으로 좁힐</b> 매니저. 오퍼레이터는 null이다.
	 *                        {@code managerId}와 값은 같지만 쓰임이 다르다 — 하나는 조인, 하나는 필터다.
	 */
	record RosterCriteria(
			UUID cohortId,
			UUID orgId,
			UUID managerId,
			UUID scopedManagerId,
			UUID assessmentRoundId,
			UUID classroomId,
			boolean unassignedOnly,
			String rawAccountStatus,
			String query,
			TraineeRosterSort sort
	) {
		public RosterCriteria(
				UUID cohortId, UUID orgId, UUID classroomId, boolean unassignedOnly,
				String rawAccountStatus, String query, TraineeRosterSort sort) {
			this(cohortId, orgId, null, null, null, classroomId, unassignedOnly,
					rawAccountStatus, query, sort);
		}
	}

	/**
	 * 명단 한 행. {@code classroomId}·{@code className}은 현재 유효한(unassigned_at IS NULL) 반 배정이
	 * 없으면 둘 다 null이다. 초대 대기는 {@code joinedAt}도 null이며, {@code leftAt}은
	 * cohort_member.status가 LEFT일 때만 값이 있다.
	 *
	 * <p>{@code inactivatedReasonCode}·{@code inactivatedAt}·{@code inactivatedById}는 계정이 INACTIVE일
	 * 때만 값이 있다 — {@code ck_app_user_status_3}이 INACTIVE인 행에 대해 셋을 NOT NULL로 강제하므로,
	 * 비활성 교육생이면 반드시 채워져 있다. {@code inactivatedReason}(상세 사유)만 NULL일 수 있다.
	 *
	 * <p>{@code inactivatedByName}은 {@code inactivatedById}가 가리키는 계정의 이름이다.
	 * {@code app_user.inactivated_by}가 {@code app_user(user_id)} FK라 조인해서 채운다.
	 */
	record RosterRow(
			UUID traineeId,
			String name,
			String email,
			String rawAccountStatus,
			UUID classroomId,
			String className,
			OffsetDateTime joinedAt,
			OffsetDateTime leftAt,
			String inactivatedReasonCode,
			String inactivatedReason,
			OffsetDateTime inactivatedAt,
			UUID inactivatedById,
			String inactivatedByName,

			/**
			 * 아직 수락·취소되지 않은 초대 토큰(11차 R2). 없으면 null이다 —
			 * 이미 활성화됐거나 초대가 취소된 계정이라 재발송할 것이 없다.
			 */
			UUID pendingInvitationTokenId,
			UUID assessmentRoundId,
			UUID attemptId,
			String roundResultStatus,
			String conceptResultItems,
			/** 2단 이하 개수의 분모. 사람마다 할당 문제 수가 다르므로 화면이 '1/2'를 그리려면 함께 필요하다. */
			Integer expectedConceptCount,
			Integer lowStageConceptCount,
			Integer excellentOccurrenceCount,
			/**
			 * 이 교육생이 우수로 발견된 프로젝트 차수(analysis_sequence_no) 전부다. <b>이번 회차를 포함한다</b> —
			 * 이번 회차가 우수인지는 이 배열에 조회 회차의 차수가 들어 있는지로 판정한다.
			 * 최신 차수부터 내림차순이며, 우수 근거가 없으면 빈 배열이다.
			 */
			int[] excellentAssessmentSequenceNos,
			String matchedRiskTypeCodes,
			/**
			 * 배지 한 칸에 넣을 <b>단일</b> 코드다. 정책 문서 §7의 2층 구조를 그대로 담는다 —
			 * 1층 응시상태(NOT_ATTENDED·SESSION_INCOMPLETE·INVALID_ATTEMPT)가 있으면 2층 위험 유형은
			 * 보지 않는다. {@code matchedRiskTypeCodes}(위험 배열)와 달리 이 값은 응시상태까지 포함한
			 * 단일 값이며, 걸린 것이 없으면 null(정상)이다.
			 *
			 * <p>중도 이탈은 여기 들어오지 않는다 — 계정 열의 비활성화 사유로 이미 드러나므로
			 * 이 배지가 또 이탈을 말할 필요가 없다.
			 */
			String roundPrimaryStatusCode,
			/**
			 * {@code roundPrimaryStatusCode}가 NOT_ATTENDED·SESSION_INCOMPLETE일 때만 값이 있는 시각이다.
			 * 화면의 '우수 누적' 칸이 그 회차엔 정상 결과 대신 '세션 중단 · {날짜}'처럼 사유·일자를
			 * 그릴 때 쓴다. 그 밖의 상태(정상 진행·완료)에서는 이 값을 쓸 일이 없어 null이다.
			 */
			OffsetDateTime roundTerminalAt,
			String rowAggregationStatus
	) {
		public RosterRow(
				UUID traineeId, String name, String email, String rawAccountStatus,
				UUID classroomId, String className, OffsetDateTime joinedAt, OffsetDateTime leftAt,
				String inactivatedReasonCode, String inactivatedReason, OffsetDateTime inactivatedAt,
				UUID inactivatedById, String inactivatedByName, UUID pendingInvitationTokenId) {
			this(traineeId, name, email, rawAccountStatus, classroomId, className, joinedAt, leftAt,
					inactivatedReasonCode, inactivatedReason, inactivatedAt, inactivatedById,
					inactivatedByName, pendingInvitationTokenId,
					null, null, null, null, null, null, null, new int[0], null, null, null, null);
		}
	}
}
