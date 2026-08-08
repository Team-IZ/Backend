package com.bigproject.backend.domain.projectexecution.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 프로젝트 상세 '현황' 화면의 반별 제출 → 분석 → 응시 진행 조회 포트.
 *
 * 원천은 assessment_round_attendance(회차 + 사용자 grain)다.
 * 뷰의 반별 윈도우 컬럼을 그대로 쓰지 않고 직접 집계하는 이유는 세 가지다.
 * - 뷰에 분석 성공 카운트가 없다. analysis_in_progress_count·analysis_failed_count만 있다.
 * - 무효 확정(validity_review_status)이 뷰 계약에 없어 measurement_attempt를 따로 조인해야 한다.
 * - 담당 매니저가 뷰에 없다.
 *
 * 제출은 team_id + assessment_round_id 단위 원장이지만 이 화면은 인원 기준으로 환산한다.
 * manager_project_submission_view는 팀 카운트만 주므로 쓸 수 없다.
 */
public interface ClassProgressQueryRepository {

	/**
	 * (project_id, round_no)는 uq_project_assessment_round_no_active로 유일해 회차가 하나로 특정된다.
	 */
	Optional<RoundScope> findRound(UUID projectId, int roundNo);

	List<ClassProgressRow> findClassProgress(UUID assessmentRoundId, UUID organizationId);

	/**
	 * 반별 합산이 아니라 회차 전체를 대상으로 직접 집계한다.
	 * classes[]는 화면 필터·정렬에 따라 달라질 수 있어 카드 요약은 이 값을 따로 쓴다.
	 */
	RoundSummaryRow findRoundSummary(UUID assessmentRoundId, UUID organizationId);

	List<ConceptMatchRow> findConceptMatches(UUID assessmentRoundId, UUID organizationId);

	/**
	 * 팀별 최신 제출·최신 분석 시도가 FAILED인 팀만 반환한다.
	 * 대표자는 submission.submitted_by이며, 재시도가 있을 수 있어 팀·회차별 최신 제출과
	 * 그 제출에 매인 최신 analysis_job만 본다 — assessment_round_attendance 뷰와 같은 선택 기준이다.
	 */
	List<FailedTeamRow> findFailedTeams(UUID assessmentRoundId, UUID organizationId);

	record RoundScope(
			UUID assessmentRoundId,
			UUID projectId,
			String projectName,
			int roundNo,
			String roundName,
			UUID organizationId,
			Instant submissionDueAt,
			String reportPublishMode,
			boolean reportPublished,
			int totalRoundCount
	) {
	}

	/**
	 * 분석 상태 네 갈래를 모두 돌려준다.
	 *
	 * PARTIAL을 분석 완료로 세지 않기로 했으므로 완료·실패만으로는 제출 인원과 등식이 성립하지 않는다.
	 * 네 값을 모두 내려야 어느 열에도 잡히지 않고 사라지는 인원이 없다.
	 * submittedCount = succeeded + failed + partial + inProgress
	 */
	record ClassProgressRow(
			UUID classId,
			String className,
			long targetTraineeCount,
			long submittedCount,
			long analysisSucceededCount,
			long analysisFailedCount,
			long analysisPartialCount,
			long analysisInProgressCount,
			long assessedCount,
			long notAttendedCount,
			long sessionIncompleteCount,
			long invalidAttemptCount,
			List<String> managerNames
	) {
	}

	/**
	 * 회차 전체 합계. analysisTargetCount는 submittedCount와, assessmentTargetCount는
	 * analysisSucceededCount와 값이 같다 — 단계별 분모를 이름으로도 명확히 드러내기 위해 따로 둔다.
	 */
	record RoundSummaryRow(
			long targetTraineeCount,
			long submittedCount,
			long analysisTargetCount,
			long analysisSucceededCount,
			long assessmentTargetCount,
			long assessedCount
	) {
	}

	record FailedTeamRow(
			UUID classId,
			UUID teamId,
			String teamName,
			UUID representativeUserId,
			String representativeName,
			String failureReason
	) {
	}

	/**
	 * 문제는 팀 공용(TEAM_SHARED_PROBLEM)이라 팀 단위 매칭 판정을 팀원 인원으로 펼쳐 센다.
	 * 분모는 분석에 성공한 인원이다.
	 */
	record ConceptMatchRow(
			UUID teachesId,
			String conceptName,
			long analysedTraineeCount,
			long matchedTraineeCount,
			long unmatchedTeamCount
	) {
	}
}
