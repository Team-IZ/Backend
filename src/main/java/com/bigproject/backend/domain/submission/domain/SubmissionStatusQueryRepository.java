package com.bigproject.backend.domain.submission.domain;

import java.time.Instant;
import java.util.Collection;
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
 *
 * <p><b>모든 조회는 매니저가 담당하는 반으로 좁힌다.</b> 기수 스코프 검사만으로는 부족하다 — 그 검사는
 * "이 기수에 담당 반이 하나라도 있는가"만 보므로, 통과한 뒤 프로젝트 전체를 읽으면 담당하지 않는 반의
 * 팀과 교육생 이름까지 함께 나간다(MG-08 §7 "매니저는 담당 반뿐").
 */
public interface SubmissionStatusQueryRepository {

	/**
	 * {@code (project_id, round_no)}는 uq_project_assessment_round_no_active로 유일해 회차가 하나로 특정된다.
	 */
	Optional<RoundScope> findRound(UUID projectId, int roundNo);

	/**
	 * 호출자가 그 반을 담당하는지. {@code classId}를 지정한 요청에서만 쓴다 — 담당 밖 반을 지정했을 때
	 * 빈 결과가 아니라 404로 끊기 위해서다. 빈 결과로 두면 화면이 "팀이 없는 회차"로 잘못 읽는다.
	 */
	boolean isClassManagedBy(UUID managerUserId, UUID classId, UUID cohortId);

	/**
	 * 팀 행. 팀·회차별 <b>최신 제출</b>({@code is_current DESC, submitted_at DESC})과 그 제출에 매인
	 * <b>최신 분석 시도</b>({@code execution_no DESC, started_at DESC})만 본다 — 재제출·재분석이 있어도
	 * 화면에는 지금 유효한 한 벌만 보여야 하고, 이 선택 기준은 {@code assessment_round_attendance}가
	 * {@code analysis_status}를 고르는 기준과 같다.
	 *
	 * <p>팀 번호는 TEXT라 사전순으로 정렬하면 10팀부터 {@code 1, 10, 11, 2}가 된다. 숫자 부분을 뽑아
	 * 먼저 정렬한다.
	 *
	 * @param classId 담당 반 중 하나로 좁힐 때만 지정한다. null이면 <b>담당 반 전체</b>다(프로젝트 전체가 아니다).
	 */
	List<TeamRow> findTeams(
			UUID projectId, UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId);

	/**
	 * 개인 행. 회차의 공식 결과인 {@code attempt_type='INITIAL'} 수행(primary_attempt) 기준이다 —
	 * RETRY·REVIEW는 결과 탭 소관이라 여기 섞지 않는다.
	 *
	 * <p>{@code teamId}가 null인 행은 아직 팀에 배정되지 않은 사람이다. 제출 현황 표는 팀 그룹 아래에만
	 * 사람을 그리므로 서비스가 버린다(미배정이 남아 있으면 애초에 이 탭이 열리지 않는다).
	 */
	List<MemberRow> findMembers(
			UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId);

	/**
	 * MG-07 목록의 진행·조치를 <b>여러 회차 몫을 한 번에</b> 집계한다(34차 R8).
	 *
	 * <h2>왜 따로 냈는가</h2>
	 *
	 * <p>목록은 행마다 진행·조치를 계산했고, 그 한 행이 {@code findRound}·{@code findTeams}·
	 * {@code findMembers}로 <b>조회 3건</b>을 돌았다. 회차가 늘면 그대로 곱해져 프론트가 잰
	 * <b>회차당 약 0.86초</b>가 됐다(고정 1.9초 + 회차당 0.86초 · 8회차 기수 7.9초).
	 *
	 * <p>게다가 목록에 필요한 것은 <b>숫자 몇 개</b>뿐인데 팀 행·개인 행을 통째로 실어 왔다.
	 * 제출 URL·제출자 이름·분석 실패 사유까지 끌고 와서 세기만 하고 버렸다. 그래서 행을
	 * 나르지 않고 <b>세어서</b> 돌려준다.
	 *
	 * <h2>기준은 상세 조회와 같다</h2>
	 *
	 * <p>「응시 완료」는 {@code terminal_at}이 있거나 {@code completion_status='COMPLETED'}이고,
	 * 「미제출」은 레코드 존재가 아니라 {@code submitted_at}으로 판정한다 — 접수 중인 제출을
	 * '냈다'로 세지 않기 위해서다. 제출·분석을 팀당 한 건씩 접는 기준도 {@link #findTeams}와
	 * 같은 {@code LATERAL}이라, 목록과 상세가 같은 팀을 두고 다른 말을 하지 않는다.
	 *
	 * <p><b>팀에 배정되지 않은 사람은 빠진다.</b> 팀을 통해서만 어느 반인지 알 수 있고, 목록의
	 * 분모도 팀이 있는 사람 기준이다 — 서비스가 하던 {@code team == null → continue}와 같다.
	 *
	 * @param assessmentRoundIds 비어 있으면 조회하지 않고 빈 목록이다
	 * @param classId            담당 반 중 하나로 좁힐 때만 지정한다. null이면 담당 반 전체
	 */
	List<ManagerProgressAggregate> findManagerProgressAggregates(
			Collection<UUID> assessmentRoundIds, UUID organizationId, UUID managerUserId, UUID classId);

	/**
	 * 회차 × 반 하나의 집계다.
	 *
	 * @param assessedCount 응시를 마친 인원. 화면 `응시 58/71`의 분자
	 * @param targetCount   그 반에서 팀에 배정된 인원. 분모
	 */
	record ManagerProgressAggregate(
			UUID assessmentRoundId,
			UUID classId,
			String className,
			long assessedCount,
			long targetCount,
			int unsubmittedTeamCount,
			int analysisFailedTeamCount,
			/**
			 * 면담 대기·진행 인원(34차 R7①). <b>팀 수가 아니라 사람 수</b>다.
			 * 판정 기준은 MG-03 면담 목록과 같다 — 제외된 후보와 끝난 면담은 뺀다.
			 */
			int interviewBacklogCount
	) {
	}

	/** 여러 프로젝트의 같은 순번 회차를 한 번에. {@link #findRound}의 묶음판이다(34차 R8). */
	List<RoundScope> findRounds(Collection<UUID> projectIds, int roundNo);

	/**
	 * 프로젝트가 정의한 요구사항 목록. 팀이 아니라 <b>프로젝트</b>에 달린 값이라 팀마다 반복해 싣지 않고
	 * 응답 최상위에 한 번만 둔다. 판정이 아직 없는 팀도 화면이 몇 건짜리 표인지 알 수 있어야 한다.
	 */
	List<RequirementRow> findRequirements(UUID projectId, UUID organizationId);

	/**
	 * 팀 × 요구사항 판정. 재분석하면 {@code assessment_version}이 올라가며 행이 쌓이므로
	 * (team, requirement)별 최신 한 건만 고른다.
	 */
	List<RequirementResultRow> findRequirementResults(
			UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId);

	/**
	 * 팀에 배정되지 않은 활성 인원 수. 팀 편성 단계를 FORMING으로 가르는 유일한 근거다.
	 *
	 * <p>🔴 <b>제출을 막지는 않는다</b>(32차 R11에서 정정). 종전 주석은 "이 값이 0이 아니면 제출
	 * 자체가 열리지 않는다"고 적었는데 사실이 아니다 — {@code SubmissionService}가 제출을 받을 때
	 * 보는 것은 회차 상태와 마감뿐이고, 이미 팀에 있는 사람은 남의 미배정과 무관하게 제출한다.
	 * 미배정인 사람 본인만 팀 컨텍스트가 없어 제출할 수 없다.
	 *
	 * <p>담당 반 기준이다 — 남의 반에 미배정 인원이 남아 있다고 이 매니저의 화면이 잠기면 안 된다.
	 */
	long countUnassignedMembers(UUID projectId, UUID organizationId, UUID managerUserId, UUID classId);

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
