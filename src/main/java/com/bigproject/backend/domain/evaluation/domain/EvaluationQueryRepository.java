package com.bigproject.backend.domain.evaluation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MG-08 프로젝트 상세 '결과' 탭의 조회 포트.
 *
 * <p>근거 뷰는 {@code manager_project_result_view}지만 <b>그 뷰를 직접 SELECT하지 않는다.</b> 두 가지가 걸린다.
 * <ul>
 *   <li>뷰가 {@code report_snapshot}을 INNER JOIN이라 <b>리포트가 만들어지기 전에는 행이 하나도 없다.</b>
 *       화면은 발행 전에도 임시 집계를 보여준다(뷰 설명 자체도 "발행 전에는 MEAS 원천 기반"이라고 적어
 *       두었는데 SQL이 그렇게 되어 있지 않다).</li>
 *   <li>뷰의 도달 단계가 {@code assessment_problem.best_success_stage}인데, 그 컬럼은
 *       {@code ck_assessment_problem_best_success_stage_2}가 <b>TEAM_SHARED_PROBLEM이면 NULL을 강제</b>한다.
 *       미니프로젝트는 전부 팀 공용 문제라 뷰로 읽으면 도달 단계가 항상 0이 된다.</li>
 * </ul>
 * 그래서 도달 단계는 {@code problem_stage}에서 직접 센다 — 제출 현황 뷰가
 * {@code problem_highest_levels}를 계산하는 것과 같은 기준이다.
 *
 * <p>공식 결과는 {@code attempt_type='INITIAL'} 수행만 쓴다. 재시험·다시 보기는 최초 결과를 덮지 않는다.
 *
 * <p>조회 범위는 <b>호출한 매니저의 담당 반</b>이다(제출 현황과 같은 규칙).
 */
public interface EvaluationQueryRepository {

	Optional<RoundScope> findRound(UUID projectId, int roundNo);

	boolean isClassManagedBy(UUID managerUserId, UUID classId, UUID cohortId);

	/** 회차 대상 인원. 응시 여부 집계의 분모이며, 결과가 없는 사람도 목록에는 남는다. */
	List<TraineeRow> findTrainees(
			UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId);

	/**
	 * 사람 × 검증 개념의 도달 결과.
	 *
	 * @param userId 개인 상세를 볼 때만 지정한다. null이면 담당 반 전원이다.
	 */
	List<ConceptResultRow> findConceptResults(
			UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId, UUID userId);

	/**
	 * 한 사람의 축별 단계 결과. <b>실제로 물은 단계만</b> 돌려준다 — 앞 단계에서 멈추면 뒤 단계는
	 * {@code NOT_REACHED}로 남고 화면에서도 '미도달' 빈 칸이라 행 자체가 없어야 한다.
	 */
	List<StageRow> findStages(UUID assessmentRoundId, UUID userId);

	/**
	 * @param reportPublished 회차 리포트가 발행됐는지. ROUND_BATCH라 회차 단위 판정이며
	 *                        {@code class-progress}의 같은 이름 값과 산식이 같다.
	 */
	record RoundScope(
			UUID assessmentRoundId,
			UUID projectId,
			UUID organizationId,
			UUID cohortId,
			String projectName,
			int roundNo,
			String roundName,
			boolean reportPublished,
			Instant publishedAt
	) {
	}

	/**
	 * @param completionStatus     NOT_STARTED · IN_PROGRESS · COMPLETED (INITIAL 수행 기준)
	 * @param terminalReasonCode   NOT_ATTENDED면 응시 창이 닫히도록 끝내 안 본 사람이다(확정 미응시).
	 * @param validityReviewStatus CONFIRMED_INVALID면 무효 확정이라 집계에서 뺀다.
	 */
	record TraineeRow(
			UUID userId,
			String name,
			UUID classId,
			String className,
			String completionStatus,
			String terminalReasonCode,
			String validityReviewStatus
	) {
	}

	/**
	 * @param generated  false면 그 개념이 코드에 없어 문제를 만들지 못했다 — <b>못한 것이 아니다.</b>
	 * @param reachLevel 통과한 축 중 가장 높은 단계(0~4). 한 단계라도 미달하면 그 문제가 끝나므로
	 *                   연속 통과 수와 같다.
	 */
	record ConceptResultRow(
			UUID userId,
			UUID conceptId,
			String conceptName,
			int displayOrder,
			UUID problemId,
			boolean generated,
			int reachLevel
	) {
	}

	/**
	 * @param axisCode  L1 · L2 · L3 · L4 (코드이해 → 설계논리 → 대안비교 → 반례대응)
	 * @param passed    그 단계를 통과했는지. 도움을 받고 통과해도 true다.
	 * @param helpCount 힌트를 받고 답한 횟수 0~2. 통과 여부와 <b>따로</b> 읽어야 한다 —
	 *                  2회까지 받고 통과할 수도, 2회 받고도 못 넘을 수도 있다.
	 * @param note      채점 근거 한 줄. 활성 스냅샷의 {@code report_evidence}
	 *                  ({@code RESULT_EXPLANATION})에서 오므로 <b>리포트 생성 전에는 null</b>이다.
	 */
	record StageRow(
			UUID problemId,
			String axisCode,
			boolean passed,
			int helpCount,
			Integer score,
			String note
	) {
	}
}
