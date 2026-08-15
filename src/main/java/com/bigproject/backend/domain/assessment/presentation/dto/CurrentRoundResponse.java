package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.AssessmentRoundStatus;
import com.bigproject.backend.domain.assessment.domain.AssessmentSessionStatus;
import com.bigproject.backend.domain.assessment.domain.MeasurementAttemptStatus;
import com.bigproject.backend.domain.assessment.domain.TraineeDefaultActionCode;
import com.bigproject.backend.domain.assessment.domain.TraineeHomeRound;
import com.bigproject.backend.domain.assessment.domain.TraineeRepresentativeStatus;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJobStatus;
import com.bigproject.backend.domain.member.domain.CommitEmailStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.reporting.domain.TraineeReleaseStatus;
import com.bigproject.backend.domain.submission.domain.SubmissionMethod;
import com.bigproject.backend.domain.submission.domain.SubmissionStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
		@Schema(description = "합성 카드일 때만 null", nullable = true) UUID assessmentRoundId,
		@Schema(nullable = true) Integer roundNo,
		@Schema(example = "미프 3차", nullable = true) String roundName,
		@Schema(description = "실제 회차면 항상 OPEN", example = "OPEN", nullable = true,
				implementation = AssessmentRoundStatus.class) String roundStatus,
		@Schema(nullable = true) UUID projectId,
		@Schema(nullable = true) String projectName,
		@Schema(nullable = true, implementation = ProjectCategory.class) String projectCategory,
		@Schema(description = "교안 표시명. 없으면 빈 배열") List<String> curriculumNames,

		@Schema(description = "회차 스코프 팀. 팀 미편성이면 null", nullable = true) UUID teamId,
		@Schema(example = "3", nullable = true) String teamNumber,
		@Schema(example = "3팀", nullable = true) String teamName,

		@Schema(description = "View 계약값 10종", example = "ANALYZING",
				implementation = TraineeRepresentativeStatus.class)
		String representativeStatus,
		@Schema(description = "View 계약값 11종", example = "WAIT_FOR_ANALYSIS",
				implementation = TraineeDefaultActionCode.class)
		String defaultActionCode,
		@Schema(description = "없으면 null", nullable = true,
				allowableValues = {"SUBMISSION_DEADLINE_PASSED", "ASSESSMENT_WINDOW_CLOSED"})
		String actionUnavailableReasonCode,
		@Schema(description = "없으면 빈 배열")
		@ArraySchema(schema = @Schema(allowableValues = {"SUBMISSION_DEADLINE_PASSED", "ANALYSIS_FAILED",
				"ASSESSMENT_WINDOW_CLOSED", "PROBLEM_NOT_GENERATED"}))
		List<String> warningCodes,

		@Schema(description = """
				커밋 이메일 상태. **null은 미등록**을 뜻한다. 배너 노출 조건은 \
				`commitEmailStatus !== "VERIFIED"`이며 빅프로젝트에서는 미등록이 기여 귀속 실패로 이어진다.""",
				example = "PENDING", nullable = true,
				implementation = CommitEmailStatus.class) String commitEmailStatus,
		@Schema(description = "기관 정책이 허용한 제출 수단")
		@ArraySchema(schema = @Schema(implementation = SubmissionMethod.class))
		List<String> availableSubmissionMethods,
		@Schema(description = "실제 제출 수단. 미제출이면 null", nullable = true,
				implementation = SubmissionMethod.class) String submissionMethod,
		@Schema(nullable = true, implementation = SubmissionStatus.class)
		String submissionStatus,
		@Schema(nullable = true) Instant submittedAt,
		@Schema(description = "마감 전이고 아직 세션을 시작하지 않았는가. 제출 버튼을 여는 값이다")
		boolean canSubmit,
		@Schema(description = "위 조건에 더해 **이미 제출이 있는가.** 버튼 문구를 「제출」과 「재제출」로 가른다")
		boolean canResubmit,

		@Schema(description = "View가 계산한 5값. 미제출이면 NOT_SUBMITTED", example = "ANALYZING",
				allowableValues = {"NOT_SUBMITTED", "ANALYZING", "FAILED", "COMPLETED", "WAITING"})
		String analysisPhase,
		@Schema(nullable = true, implementation = AnalysisJobStatus.class)
		String analysisJobStatus,
		@Schema(description = """
				분석이 실패했는가만 알린다. 값은 `ANALYSIS_FAILED` **한 종류**이고 실패하지 않았으면 `null`이다.

				⚠️ 사유 코드가 아니다. `trainee_home_round_view`가
				`CASE WHEN analysis_status='FAILED' THEN 'ANALYSIS_FAILED' ELSE NULL END`로 만든 상수다.
				**실제 사유는 TR-02(`GET /projects/{projectId}/submissions/me`)의 `analysisFailureCode`에 있다** —
				사유 문구를 두 화면에 복제하면 같은 실패가 화면마다 다르게 설명된다.""",
				nullable = true, allowableValues = {"ANALYSIS_FAILED"})
		String analysisFailureCode,

		// 19차 R3로 둘 다 확정했다. 17차에서 "등"이라 보류했는데, 확인해 보니 두 값 집합 모두
		// DB CHECK로 이미 닫혀 있었고 문서만 열려 있었다.
		//
		// 🔴 종전 설명문이 둘 다 "READY · IN_PROGRESS · PAUSED · COMPLETED 등"이었는데
		// initialAttemptStatus 쪽은 틀린 문구였다 — 그 필드는 세션이 아니라 응시 상태
		// (measurement_attempt.status)이고 값이 아예 다르다.
		@Schema(description = "첫 응시 상태. **세션 상태가 아니다**", nullable = true,
				implementation = MeasurementAttemptStatus.class)
		String initialAttemptStatus,
		@Schema(description = "첫 응시의 세션 상태. 문제를 푸는 구간만 가리킨다", nullable = true,
				implementation = AssessmentSessionStatus.class)
		String initialSessionStatus,
		// 종전 설명이 "출제된 문제 수", example=3 이었는데 뷰가 NOT_GENERATED 슬롯까지 세어
		// 실제로 항상 3이 나갔다(2026-08-15 실측: GENERATED 1건인데 3). View 쪽을 고쳤으므로
		// 이제 세션 API의 problemTotal과 같은 수다.
		@Schema(description = """
				실제로 출제된 문제 수(`0`~`3`). 세션 API의 `problemTotal`과 같다.

				⚠️ **`3`으로 가정하지 말 것.** 코드에 근거가 없는 검증 개념은 문항이 만들어지지 않아
				(`NOT_GENERATED`) 세션에 나오지 않는다. 세션이 열리기 전에는 `0`이다.""",
				example = "1") int preparedProblemCount,
		// 19차 R3 회신으로 값 집합을 확정했다 — View의 latest_review_status가 REVIEW 응시의
		// measurement_attempt.status를 그대로 옮긴 값이라 응시 상태 기계와 1:1이다.
		// "배정 없음"은 값이 아니라 null이다(TraineeReviewStatus javadoc 참고).
		@Schema(description = "다시 보기 응시 상태. **배정이 없으면 null**", nullable = true,
				implementation = MeasurementAttemptStatus.class) String reviewStatus,
		int completedReviewCount,

		@Schema(nullable = true) UUID reportId,
		@Schema(allowableValues = {"PUBLISHED", "GENERATING", "NOT_PUBLISHED"}) String reportPublishStatus,
		@Schema(description = "리포트 행이 없으면 NOT_CONFIGURED로 정규화한다",
				implementation = TraineeReleaseStatus.class) String traineeReleaseStatus,
		@Schema(description = "traineeReleaseStatus = RELEASED일 때만 true") boolean canViewReport,
		@Schema(allowableValues = {"UNAVAILABLE", "PARTIAL", "AVAILABLE"}) String explanationStatus,

		@Schema(nullable = true) Instant submissionDueAt,
		@Schema(description = """
				🔴 **폐기된 필드. 언제나 `null`이다**(2026-08-16).

				회차 공통 응시 창 시작이었다. 컬럼이 폐기돼 `project_assessment_round`의 전 행이
				`null`이며, 다시 채우는 코드 경로도 없다. 계약은 화면이 깨지지 않도록 남겨 두지만
				**응시 가능 판정에 쓰지 말 것** — 개인 창(`assessmentOpenAt`)만 보면 된다.""",
				nullable = true) Instant roundAssessmentOpenAt,
		@Schema(description = """
				🔴 **폐기된 필드. 언제나 `null`이다**(2026-08-16). `roundAssessmentOpenAt`과 같다.

				응시 마감은 개인 창(`assessmentCloseAt`) 하나로 정해진다.""",
				nullable = true) Instant roundAssessmentDueAt,
		@Schema(description = """
				개인 응시 창 시작. 분석이 끝나 세션이 열린 시각이며, 수행 생성 전이면 null이다.

				**응시 가능 여부를 정하는 것은 이 값과 `assessmentCloseAt`뿐이다.**""",
				nullable = true) Instant assessmentOpenAt,
		@Schema(description = """
				개인 응시 창 종료. 세션이 열린 시각부터 24시간이며(`assessment.window-hours`),
				수행 생성 전이면 null이다.

				## 🔴 응시 창은 이제 하나다 — 개인 창이 정본

				종전에는 개인 창과 회차 창의 **교집합**으로 판정하라고 적었다. 회차 창
				(`roundAssessmentOpenAt`·`roundAssessmentDueAt`)이 2026-08-16에 폐기돼 **언제나 `null`**
				이므로 그 식은 성립하지 않는다.

				```
				열림  = assessmentOpenAt
				닫힘  = assessmentCloseAt
				```

				⚠️ **`min(assessmentCloseAt, roundAssessmentDueAt)`을 그대로 쓰면 안 된다.**
				JS에서 `Math.min(x, null)`은 `null`을 `0`으로 바꿔 **마감이 1970-01-01로 그려진다.**
				회차 창을 참조하는 코드가 남아 있으면 걷어낼 것.

				남은 시간 문구는 `assessmentCloseAt` 하나로 그린다. 서버도 같은 값으로 막는다 —
				창이 지난 뒤 세션 API는 409 `ASSESSMENT_WINDOW_CLOSED`를 낸다.""",
				nullable = true) Instant assessmentCloseAt,
		@Schema(nullable = true) Instant initialTerminalAt,
		@Schema(example = "ROUND_BATCH", nullable = true, allowableValues = {"ROUND_BATCH"})
		String reportPublishMode,
		@Schema(nullable = true) Instant reportPublishNotBeforeAt,

		@Schema(nullable = true) ManagerResponse manager,
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
