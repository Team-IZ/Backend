package com.bigproject.backend.domain.reporting.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TR-04 `내 리포트` 조회 포트. 교육생 본인의 회차·개념·문답을 읽는다.
 *
 * <h2>왜 JPA가 아니라 읽기 전용 SQL인가</h2>
 *
 * <p>v07 스키마에 이 화면 전용 뷰가 이미 있다 — {@code trainee_report_round_view},
 * {@code trainee_report_view}, {@code trainee_report_problem_view}. 공개 범위에 따른
 * 노출 판정({@code can_view_own_answers} 등)이 <b>뷰 안에 들어 있어서</b>, 베이스 테이블을
 * 다시 조인하면 판정 로직이 두 벌로 갈라진다. usagemetering의 {@code OperationsCostRepository}와
 * 같은 이유로 포트만 도메인에 두고 SQL은 infrastructure에 둔다.
 *
 * <p>회차·프로젝트·응시 정보는 Academic Operations·Assessment 도메인 소유라
 * 엔티티로 매핑하지 않고 값만 읽는다.
 */
public interface TraineeReportQueryRepository {

	/** 교육생의 회차 목록 + 각 회차의 리포트 상태. 최신 회차가 앞이다. */
	List<RoundRow> findRounds(UUID userId);

	/** 교육생의 개념별 결과. 회차(reportId)로 묶어 쓴다. */
	List<ConceptRow> findConcepts(UUID userId);

	/**
	 * 문답 원문. <b>공개 범위가 FULL인 리포트만</b> 돌려준다
	 * ({@code trainee_report_view.can_view_own_answers} 규칙을 SQL에 그대로 옮겼다).
	 * SUMMARY면 개념·도달까지만 보이고 답변 원문은 나가지 않는다.
	 */
	List<StageAnswerRow> findStageAnswers(UUID userId);

	/**
	 * <b>묻지 못한 개념</b>({@code assessment_problem.generation_status='NOT_GENERATED'}).
	 *
	 * <p>{@link #findConcepts}와 따로 읽는 이유는 원천이 다르기 때문이다. 그쪽은
	 * {@code trainee_report_problem_view}이고 그 뷰는 {@code report_evidence}에서 시작하는데,
	 * 문항이 만들어지지 않은 개념에는 근거 행이 없어 뷰에 아예 나타나지 않는다.
	 *
	 * <p>그래서 이 조회가 없으면 화면에 개념이 <b>2개만</b> 뜨고 학생은 나머지 하나가 어디 갔는지
	 * 알 수 없다. {@code GET /reports/class-diagnosis}가 {@code level0}과 {@code unasked}를 엄격히
	 * 구분하는 것과 같은 이유로, 교육생 리포트도 "못한 것"과 "안 물어본 것"을 갈라야 한다 —
	 * 뷰가 {@code reach_display_code}를 {@code COALESCE(best_success_stage,'L0')}으로 만들기 때문에
	 * 둘을 합치면 <b>묻지 않은 개념이 0단으로 표시된다.</b>
	 */
	List<UnaskedConceptRow> findUnaskedConcepts(UUID userId);

	/**
	 * 묻지 못한 개념 하나.
	 *
	 * @param reasonCode {@code NO_MATCHING_CODE_EVIDENCE} 하나로 고정돼 있다
	 *                   ({@code ck_assessment_problem_not_generated_reason_code}).
	 */
	record UnaskedConceptRow(
			UUID reportId,
			UUID problemId,
			String conceptDisplayName,
			int conceptDisplayOrder,
			String reasonCode
	) {
	}

	/**
	 * {@code trainee_report_round_view} 한 행.
	 *
	 * @param assessmentRoundId 회차 선택의 <b>권위 식별자</b>. reportId는 없을 수도 있는 보조 키다
	 *                          (뷰 명세 "회차 선택 권위 식별자는 assessment_round_id").
	 * @param attemptId         응시 기록. null이면 미응시다.
	 * @param terminalReasonCode measurement_attempt 종료 사유. 중단·미제출 판정에 쓴다.
	 * @param validityReviewStatus 무효 응시 검토 상태. PENDING·CONFIRMED_INVALID면 화면이 `확인 필요`다.
	 * @param completionStatus  활성 스냅샷의 {@code FULL}·{@code PARTIAL}. 스냅샷이 없으면 null이다.
	 *                          {@code ck_report_snapshot_completion_status}가 두 값만 허용한다.
	 * @param reportPublishNotBeforeAt 발행 예정 시각. 화면 `PENDING_PUBLISH`의 `publishAfter`.
	 * @param reviewStatus      다시 보기(REVIEW attempt) 상태. 없으면 null.
	 */
	record RoundRow(
			UUID assessmentRoundId,
			String roundName,
			int roundNo,
			String projectName,
			UUID reportId,
			UUID snapshotId,
			String completionStatus,
			UUID attemptId,
			String attemptStatus,
			String terminalReasonCode,
			String validityReviewStatus,
			String traineeReleaseStatus,
			String traineeDisclosureScope,
			Instant reportPublishNotBeforeAt,
			Instant publishedAt,
			boolean canViewReport,
			String reviewStatus,
			Instant reviewDueAt,
			Instant reviewCompletedAt
	) {
	}

	/**
	 * {@code trainee_report_problem_view} 한 행 = 개념 하나.
	 *
	 * @param reachDisplayCode `L0`~`L4`. <b>L0은 통과한 축이 하나도 없다는 뜻</b>이며
	 *                         0으로 내보낸다(5단 계약).
	 * @param curriculumLocationJson 교안 위치 JSON 원문. 공개 범위가 SUMMARY 이상일 때만 채워진다.
	 * @param reviewBeforeAfterItemsJson 다시 보기 전/후 비교. 화면 `comparedReach`의 원천.
	 */
	record ConceptRow(
			UUID reportId,
			UUID problemId,
			String conceptDisplayName,
			int conceptDisplayOrder,
			String reachDisplayCode,
			String resultExplanation,
			String answerExcerpt,
			String curriculumLocationJson,
			boolean reviewRequired,
			String reviewBeforeAfterItemsJson,
			boolean canViewExplanation
	) {
	}

	/**
	 * 문답 한 슬롯. {@code problem_stage} 한 행이 최대 3슬롯(질문·힌트1·힌트2)을 담으므로
	 * SQL에서 슬롯을 행으로 펼쳐서 돌려준다 — 화면 `qa[]`가 슬롯 단위이기 때문이다.
	 *
	 * @param slotCode `QUESTION` · `FIRST_HINT` · `SECOND_HINT`
	 */
	record StageAnswerRow(
			UUID reportId,
			UUID problemId,
			String axisCode,
			int slotOrder,
			String slotCode,
			String questionText,
			String answerText
	) {
	}
}
