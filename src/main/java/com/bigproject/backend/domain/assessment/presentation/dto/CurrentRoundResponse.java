package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.TraineeHomeRound;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "지금 할 일" 카드. 35필드.
 *
 * <p>회차가 없으면 {@link #noActiveRound(Instant)}로 합성한다. 이때 식별·일정 필드는 모두 null이고
 * {@code representativeStatus}만 {@code NO_ACTIVE_ROUND}, {@code defaultActionCode}는 {@code NONE}이다.
 * View는 이 상태를 만들지 못한다 — 진행 중인 프로젝트가 없으면 원천이 0행을 반환하기 때문이다.
 *
 * <p>4단계(코드 제출 → 코드 분석 → 이해도 확인 → 리포트) 진행 표시는 파생 필드 없이
 * 아래 원시 상태값으로 클라이언트가 판정한다.
 * <ul>
 *   <li>① {@code submissionStatus = 'ACCEPTED'}</li>
 *   <li>② {@code analysisPhase = 'COMPLETED'}</li>
 *   <li>③ {@code initialAttemptStatus = 'COMPLETED'}</li>
 *   <li>④ {@code canViewReport = true}</li>
 * </ul>
 */
@Schema(description = "지금 할 일 카드. 회차가 없으면 NO_ACTIVE_ROUND 합성 카드가 들어간다.")
public record CurrentRoundResponse(
		@Schema(description = "합성 카드일 때만 null") UUID assessmentRoundId,
		Integer roundNo,
		@Schema(example = "미프 3차") String roundName,
		@Schema(description = "실제 회차면 항상 OPEN", example = "OPEN") String roundStatus,
		UUID projectId,
		String projectName,
		@Schema(example = "MINI_PROJECT") String projectCategory,
		@Schema(description = "교안 표시명. 없으면 빈 배열") List<String> curriculumNames,

		@Schema(description = "회차 스코프 팀. 팀 미편성이면 null") UUID teamId,
		@Schema(example = "3") String teamNumber,
		@Schema(example = "3팀") String teamName,

		@Schema(description = "View 계약값 10종", example = "ANALYZING") String representativeStatus,
		@Schema(description = "View 계약값 11종", example = "WAIT_FOR_ANALYSIS") String defaultActionCode,
		String actionUnavailableReasonCode,
		@Schema(description = "없으면 빈 배열") List<String> warningCodes,

		@Schema(description = """
				커밋 이메일 상태. **null은 미등록**을 뜻한다. 배너 노출 조건은 \
				`commitEmailStatus !== "VERIFIED"`이며 빅프로젝트에서는 미등록이 기여 귀속 실패로 이어진다.""",
				example = "PENDING") String commitEmailStatus,
		@Schema(description = "기관 정책이 허용한 제출 수단") List<String> availableSubmissionMethods,
		String submissionMethod,
		String submissionStatus,
		Instant submittedAt,
		boolean canSubmit,
		boolean canResubmit,

		@Schema(description = "View가 계산한 5값. 미제출이면 NOT_SUBMITTED", example = "ANALYZING") String analysisPhase,
		String analysisJobStatus,
		String analysisFailureCode,

		String initialAttemptStatus,
		String initialSessionStatus,
		@Schema(description = "출제된 문제 수", example = "3") int preparedProblemCount,
		String reviewStatus,
		int completedReviewCount,

		UUID reportId,
		String reportPublishStatus,
		@Schema(description = "리포트 행이 없으면 NOT_CONFIGURED로 정규화한다") String traineeReleaseStatus,
		@Schema(description = "traineeReleaseStatus = RELEASED일 때만 true") boolean canViewReport,
		String explanationStatus,

		Instant submissionDueAt,
		@Schema(description = "OPEN 회차면 DB가 non-null을 보장한다") Instant roundAssessmentOpenAt,
		@Schema(description = "OPEN 회차면 DB가 non-null을 보장한다") Instant roundAssessmentDueAt,
		@Schema(description = "개인 응시 창 시작. 수행 생성 전이면 null") Instant assessmentOpenAt,
		@Schema(description = "개인 응시 창 종료. 수행 생성 전이면 null") Instant assessmentCloseAt,
		Instant initialTerminalAt,
		@Schema(example = "ROUND_BATCH") String reportPublishMode,
		Instant reportPublishNotBeforeAt,

		ManagerResponse manager,
		@Schema(description = "서버 조회 시각") Instant asOfAt
) {
	private static final String TRAINEE_RELEASE_STATUS_NOT_CONFIGURED = "NOT_CONFIGURED";

	public static CurrentRoundResponse from(TraineeHomeRound round, List<String> availableSubmissionMethods) {
		return new CurrentRoundResponse(
				round.assessmentRoundId(),
				round.roundNo(),
				round.roundName(),
				round.roundStatus(),
				round.projectId(),
				round.projectName(),
				round.projectCategory(),
				round.curriculumNames(),

				round.teamId(),
				round.teamNumber(),
				round.teamName(),

				round.representativeStatus(),
				round.defaultActionCode(),
				round.actionUnavailableReasonCode(),
				round.warningCodes(),

				round.commitEmailStatus(),
				availableSubmissionMethods,
				round.submissionMethod(),
				round.submissionStatus(),
				round.submittedAt(),
				round.canSubmit(),
				round.canResubmit(),

				round.analysisPhase(),
				round.analysisJobStatus(),
				round.analysisFailureCode(),

				round.initialAttemptStatus(),
				round.initialSessionStatus(),
				round.preparedProblemCount(),
				round.reviewStatus(),
				round.completedReviewCount(),

				round.reportId(),
				round.reportPublishStatus(),
				normalizeTraineeReleaseStatus(round.traineeReleaseStatus()),
				round.canViewReport(),
				round.explanationStatus(),

				round.submissionDueAt(),
				round.roundAssessmentOpenAt(),
				round.roundAssessmentDueAt(),
				round.assessmentOpenAt(),
				round.assessmentCloseAt(),
				round.initialTerminalAt(),
				round.reportPublishMode(),
				round.reportPublishNotBeforeAt(),

				toManager(round),
				round.asOfAt()
		);
	}

	/** 진행 중인 회차가 없을 때 서버가 만드는 카드. View는 이 상태를 생성하지 못한다. */
	public static CurrentRoundResponse noActiveRound(Instant asOfAt) {
		return new CurrentRoundResponse(
				null, null, null, null, null, null, null, List.of(),
				null, null, null,
				"NO_ACTIVE_ROUND", "NONE", null, List.of(),
				null, List.of(), null, null, null, false, false,
				"NOT_SUBMITTED", null, null,
				null, null, 0, null, 0,
				null, "NOT_PUBLISHED", TRAINEE_RELEASE_STATUS_NOT_CONFIGURED, false, "UNAVAILABLE",
				null, null, null, null, null, null, null, null,
				null, asOfAt
		);
	}

	/**
	 * 리포트 행이 없으면 View의 {@code trainee_release_status}가 NULL이다.
	 * 클라이언트 분기를 하나로 유지하려고 {@code NOT_CONFIGURED}로 정규화한다.
	 */
	private static String normalizeTraineeReleaseStatus(String traineeReleaseStatus) {
		return traineeReleaseStatus == null ? TRAINEE_RELEASE_STATUS_NOT_CONFIGURED : traineeReleaseStatus;
	}

	private static ManagerResponse toManager(TraineeHomeRound round) {
		if (round.managerUserId() == null) {
			return null;
		}
		return new ManagerResponse(round.managerUserId(), round.managerName());
	}
}
