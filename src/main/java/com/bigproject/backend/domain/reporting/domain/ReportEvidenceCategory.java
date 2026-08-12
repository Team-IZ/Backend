package com.bigproject.backend.domain.reporting.domain;

/**
 * DB CHECK: ck_report_evidence_evidence_category — 8종.
 *
 * <p>개인 리포트가 쓰는 것은 앞의 셋뿐이다. {@code trainee_report_problem_view}가
 * {@code evidence_category IN ('ANSWER_EXCERPT','RESULT_EXPLANATION','CURRICULUM_LOCATION')}으로
 * 거른다. 나머지 다섯은 기수·반 단위 리포트 전용이다.
 *
 * <p>배치가 실제로 쓰는 값은 {@link #RESULT_EXPLANATION} 하나다 — 뷰가 한 행에서
 * {@code evidence_summary}·{@code quote_excerpt}·{@code trace_payload}를 모두 읽으므로
 * 셋을 따로 쓸 이유가 없고, 오히려 <b>문제 하나가 화면에 세 번 뜬다</b>
 * ({@link ReportEvidence} javadoc 참고).
 */
public enum ReportEvidenceCategory {

	/** 문답 발췌. `num_nonnulls(problem_id, problem_stage_id) > 0`을 CHECK가 요구한다. */
	ANSWER_EXCERPT,

	/** 결과 설명. 개인 리포트의 개념 카드 1장이 이 행 하나다. */
	RESULT_EXPLANATION,

	/** 교안 위치. */
	CURRICULUM_LOCATION,

	/** 이하 기수·반 단위 리포트 전용. 값 집합 정합을 위해 유지한다. */
	METRIC_TRACE,
	REPORT_SOURCE_ROUND,
	REPORT_SOURCE_SNAPSHOT,
	PARTICIPANT_RESULT,
	PARTICIPANT_RESULT_OCCURRENCE
}
