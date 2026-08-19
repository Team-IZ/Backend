package com.bigproject.backend.domain.assessment.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code trainee_home_round_view} 한 행. 교육생 홈 회차 카드의 단일 원천이다.
 *
 * <p>View 69컬럼 중 홈 3구획이 실제로 쓰는 것만 담는다. {@code representativeStatus}와
 * {@code defaultActionCode}는 View가 계산한 계약값이므로 서버가 다시 파생시키지 않는다.
 *
 * <p>팀 3필드는 회차 스코프다. View의 원본 컬럼명이 {@code team_id_at_round}인 데서 보이듯
 * {@code assessment_round_attendance}가 회차의 {@code submission_due_at} 시점으로 소속을
 * 확정하므로, 같은 교육생이라도 회차마다 팀이 다를 수 있다.
 */
public record TraineeHomeRound(
		UUID assessmentRoundId,
		Integer roundNo,
		/**
		 * 기수 안 운영 순서({@code project.sequence_no}). <b>회차 정렬의 유일한 축이다.</b>
		 * {@code roundNo}는 프로젝트 안에서만 유일해 미니프로젝트에서는 전부 1이다 —
		 * 자세한 이유는 {@code JdbcTraineeHomeRoundRepository.FIND_ROUNDS} 주석에 있다.
		 */
		Integer projectSequenceNo,
		String roundName,
		String roundStatus,
		UUID projectId,
		String projectName,
		String projectCategory,
		List<String> curriculumNames,

		UUID cohortId,
		String cohortName,
		UUID classId,
		String className,
		UUID teamId,
		String teamNumber,
		String teamName,

		String representativeStatus,
		String defaultActionCode,
		String actionUnavailableReasonCode,
		List<String> warningCodes,

		String commitEmailStatus,
		String submissionMethod,
		String submissionStatus,
		Instant submittedAt,
		boolean canSubmit,
		boolean canResubmit,

		String analysisPhase,
		String analysisJobStatus,
		String analysisFailureCode,

		String initialAttemptStatus,
		String initialSessionStatus,
		int preparedProblemCount,
		String reviewStatus,
		int completedReviewCount,

		UUID reportId,
		String reportPublishStatus,
		String explanationStatus,

		Instant submissionDueAt,
		Instant roundAssessmentOpenAt,
		Instant roundAssessmentDueAt,
		Instant assessmentOpenAt,
		Instant assessmentCloseAt,
		Instant initialTerminalAt,
		String reportPublishMode,
		Instant reportPublishNotBeforeAt,

		UUID managerUserId,
		String managerName,
		Instant asOfAt
) {
	private static final String ROUND_STATUS_OPEN = "OPEN";
	private static final String ROUND_STATUS_PLANNED = "PLANNED";
	private static final String REPORT_PUBLISH_STATUS_PUBLISHED = "PUBLISHED";

	public boolean isOpen() {
		return ROUND_STATUS_OPEN.equals(roundStatus);
	}

	public boolean isPlanned() {
		return ROUND_STATUS_PLANNED.equals(roundStatus);
	}

	public boolean isPast() {
		return "CLOSED".equals(roundStatus) || "COMPLETED".equals(roundStatus);
	}

	/**
	 * 제출 마감이 지났는가.
	 *
	 * <p>기준 시각은 View가 준 {@code as_of_at}(= DB의 {@code CURRENT_TIMESTAMP})을 넘겨받는다.
	 * {@code Instant.now()}를 쓰지 않는 이유는 같은 응답 안의 {@code can_submit} ·
	 * {@code representative_status}가 이미 그 시각으로 판정돼 있기 때문이다 — 서버 시계로 다시
	 * 재면 "마감 전이라 예정 구획에 있는데 제출은 못 하는" 카드가 생길 수 있다.
	 *
	 * <p>마감 시각이 없는 회차({@code PLANNED}에서 가능)는 아직 안 지난 것으로 본다.
	 */
	public boolean isSubmissionClosedAt(Instant asOfAt) {
		return submissionDueAt != null && asOfAt != null && submissionDueAt.isBefore(asOfAt);
	}

	/**
	 * 리포트 열람 허용 여부. <b>발행됐는가</b> 하나다.
	 *
	 * <p>종전 근거는 {@code traineeReleaseStatus = 'RELEASED'}였다. 공개/비공개가 폐지되면서
	 * (2026-08-19) 발행이 곧 공개가 됐고, 그 값을 읽던 자리를 {@code reportPublishStatus}가
	 * 물려받았다 — 뷰에서 {@code published_at IS NOT NULL}일 때 {@code PUBLISHED}이므로
	 * <b>같은 컬럼을 보는 것</b>이다.
	 *
	 * <p>View가 {@code default_action_code = 'VIEW_REPORT'}를 만들 때 쓰는 조건과 같은 근거를
	 * 써야 계약이 갈리지 않는다. 마이그레이션 4절이 그 조건도 {@code published_at}으로 옮겼다.
	 */
	public boolean canViewReport() {
		return REPORT_PUBLISH_STATUS_PUBLISHED.equals(reportPublishStatus);
	}
}
