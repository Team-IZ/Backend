package com.bigproject.backend.domain.reporting.domain;

/**
 * {@code report_evidence.decision_code} 중 개인 리포트가 쓰는 2종. 다시 보기 대상 여부다.
 *
 * <p>⚠️ 이 컬럼에는 <b>DDL CHECK가 없다.</b> 값 집합의 근거는 두 가지다 —
 * {@code trainee_report_problem_view}가 {@code re.decision_code='REVIEW_REQUIRED'}로 판정하고
 * {@code COALESCE(re.decision_code,'NOT_REQUIRED')}로 기본값을 두는 것, 그리고 시드
 * {@code docs/dummy-data/enrich-report-domain.sql}이 이 둘만 쓰는 것이다.
 */
public enum ReportEvidenceDecision {

	/** 다시 보기 대상. 화면이 개념 카드를 경고색으로 그린다. */
	REVIEW_REQUIRED,

	/** 대상 아님. 뷰의 COALESCE 기본값과 같은 값이다. */
	NOT_REQUIRED
}
