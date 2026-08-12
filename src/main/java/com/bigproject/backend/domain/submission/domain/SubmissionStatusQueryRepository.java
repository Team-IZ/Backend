package com.bigproject.backend.domain.submission.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MG-08 프로젝트 상세 '제출 현황' 탭의 조회 포트.
 *
 * <p>근거 뷰는 {@code manager_project_submission_view}지만 <b>그 뷰를 직접 SELECT하지 않는다.</b>
 * 뷰의 요구사항 LATERAL이 {@code ORDER BY sequence_no, assessed_at DESC LIMIT 1}이라 팀당 판정이
 * 1건만 나오는데, 화면은 팀 행을 펼쳐 요구사항 <b>전건</b>을 보여준다. 또 뷰의 행 grain이 팀 × 팀원이라
 * 요구사항을 같은 행에 붙이면 곱집합이 된다. 그래서 뷰는 <b>판정 기준의 계약서</b>로만 읽고
 * (최신 제출·최신 분석을 무엇으로 고르는지, 팀 편성 단계를 어떻게 가르는지) 조회는 grain별로 나눠 던진 뒤
 * 서비스에서 조립한다.
 *
 * <p>제출은 {@code team_id + assessment_round_id} 단위 원장이고 응시는 개인 단위라, 팀 행과 개인 행이
 * 애초에 다른 grain이다. 화면의 2계층 표가 그 사실을 그대로 드러낸 것이다.
 */
public interface SubmissionStatusQueryRepository {

	/**
	 * {@code (project_id, round_no)}는 uq_project_assessment_round_no_active로 유일해 회차가 하나로 특정된다.
	 */
	Optional<RoundScope> findRound(UUID projectId, int roundNo);

	/**
	 * 팀 행. 팀·회차별 <b>최신 제출</b>({@code is_current DESC, submitted_at DESC})과 그 제출에 매인
	 * <b>최신 분석 시도</b>({@code execution_no DESC, started_at DESC})만 본다 — 재제출·재분석이 있어도
	 * 화면에는 지금 유효한 한 벌만 보여야 하고, 이 선택 기준은 {@code assessment_round_attendance}가
	 * {@code analysis_status}를 고르는 기준과 같다.
	 *
	 * @param classId 담당 반 중 하나로 좁힐 때만 지정한다. null이면 프로젝트의 모든 반이다.
	 */
	List<TeamRow> findTeams(UUID projectId, UUID assessmentRoundId, UUID organizationId, UUID classId);

	/**
	 * 개인 행. 회차의 공식 결과인 {@code attempt_type='INITIAL'} 수행(primary_attempt) 기준이다 —
	 * RETRY·REVIEW는 결과 탭 소관이라 여기 섞지 않는다.
	 *
	 * <p>{@code teamId}가 null인 행은 아직 팀에 배정되지 않은 사람이다. 제출 현황 표는 팀 그룹 아래에만
	 * 사람을 그리므로 서비스가 버린다(미배정이 남아 있으면 애초에 이 탭이 열리지 않는다).
	 */
	List<MemberRow> findMembers(UUID assessmentRoundId, UUID organizationId, UUID classId);

	/**
	 * 프로젝트가 정의한 요구사항 목록. 팀이 아니라 <b>프로젝트</b>에 달린 값이라 팀마다 반복해 싣지 않고
	 * 응답 최상위에 한 번만 둔다. 판정이 아직 없는 팀도 화면이 몇 건짜리 표인지 알 수 있어야 한다.
	 */
	List<RequirementRow> findRequirements(UUID projectId, UUID organizationId);

	/**
	 * 팀 × 요구사항 판정. 재분석하면 {@code assessment_version}이 올라가며 행이 쌓이므로
	 * (team, requirement)별 최신 한 건만 고른다.
	 */
	List<RequirementResultRow> findRequirementResults(UUID assessmentRoundId, UUID organizationId, UUID classId);

	/**
	 * 팀에 배정되지 않은 활성 인원 수. 팀 편성 단계를 FORMING으로 가르는 유일한 근거이며,
	 * 이 값이 0이 아니면 제출 자체가 열리지 않는다.
	 */
	long countUnassignedMembers(UUID projectId, UUID organizationId, UUID classId);

	/**
	 * @param projectLifecycleStatus PLANNED · RUNNING · CLOSED. 종료된 회차는 화면이 편성 액션을 잠근다.
	 */
	record RoundScope(
			UUID assessmentRoundId,
			UUID projectId,
			UUID organizationId,
			UUID cohortId,
			String projectName,
			int roundNo,
			String roundName,
			Instant submissionDueAt,
			String projectLifecycleStatus
	) {
	}

	/**
	 * @param submissionId  null이면 그 팀은 아직 제출 전이다. <b>레코드 존재가 아니라 이 값으로 판정한다.</b>
	 * @param repositoryUrl ZIP 제출은 Repository가 없어 null이다(제출 수단은 submissionMethod로 구분한다).
	 * @param analysisStatus QUEUED · RUNNING · SUCCEEDED · PARTIAL · FAILED. 원값 그대로 내보낸다 —
	 *                       PARTIAL을 완료로 접으면 화면이 실패도 완료도 아닌 인원을 잃는다.
	 */
	record TeamRow(
			UUID teamId,
			UUID classId,
			String className,
			String teamNumber,
			String teamName,
			String teamStatus,
			UUID submissionId,
			Instant submittedAt,
			String repositoryUrl,
			UUID submittedByUserId,
			String submittedByName,
			String submissionMethod,
			String submissionStatus,
			UUID analysisJobId,
			String analysisStatus,
			String analysisFailureCode,
			String analysisFailureReason
	) {
	}

	/**
	 * @param completedAt {@code measurement_attempt.terminal_at}. 응시를 실제로 마친 시각이며 화면의
	 *                    '제출' 열이 개인 행에서 보여주는 값이다. 종료되지 않았으면 null이다.
	 */
	record MemberRow(
			UUID teamId,
			UUID userId,
			String userName,
			UUID primaryAttemptId,
			String primaryAttemptStatus,
			String completionStatus,
			Instant assessmentOpenAt,
			Instant assessmentCloseAt,
			Instant completedAt
	) {
	}

	record RequirementRow(
			UUID requirementId,
			String requirementKey,
			int sequenceNo,
			String title,
			String description
	) {
	}

	/**
	 * @param result     PENDING · PASS · FAIL
	 * @param evidence   {@code evidence_summary}. 왜 그 판정인지를 사람이 읽는 한 줄이다.
	 * @param judgedByAi {@code assessed_by IS NULL}. 기존
	 *                   {@code GET /submissions/{id}/analysis/result}와 같은 산식이다.
	 */
	record RequirementResultRow(
			UUID teamId,
			UUID requirementId,
			String requirementKey,
			String title,
			int sequenceNo,
			String result,
			String evidence,
			boolean judgedByAi
	) {
	}
}
