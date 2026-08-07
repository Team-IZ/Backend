package com.bigproject.backend.domain.reporting.domain;

/**
 * report.report_type에 저장되는 값. DB CHECK(ck_report_report_type)와 1:1로 맞춘다.
 *
 * <p>화면과의 대응은 아래와 같다.
 *
 * <ul>
 *   <li>{@link #TRAINEE_FINAL} — TR-04 `내 리포트`. 교육생 1명 × 회차 1건이다.</li>
 *   <li>{@link #COHORT_CURRICULUM_DIAGNOSIS} — OP-05 리포트. 기수 전체의 개념별 도달 분포.</li>
 * </ul>
 *
 * <p>⚠ {@link #COHORT_OUTCOME}(기수 결산)은 <b>현재 만들지 않는다.</b> 원래 OP-05의
 * `미프→빅프 성장` 섹션을 채우려던 타입인데, 빅프로젝트가 제품에서 빠지면서(Frontend #93·#94)
 * "이후" 상태가 없어져 성장 지표 자체가 성립하지 않는다. 프론트도 GrowthFlow 컴포넌트를
 * 파일째 삭제했다. DB CHECK에 값이 남아 있어 enum도 유지하지만 <b>생성 경로가 없다.</b>
 *
 * <p>{@link #COHORT_SUMMARY}·{@link #CHECKPOINT}도 v07 시점에 쓰는 화면이 없다.
 * CHECK와의 정합을 위해 값만 유지한다.
 */
public enum ReportType {

	/** 교육생 1명의 회차 1건 리포트. TR-04가 읽는 유일한 타입이다. */
	TRAINEE_FINAL,

	/** 기수 단위 개념별 도달 분포(수업 진단). OP-05가 읽는다. */
	COHORT_CURRICULUM_DIAGNOSIS,

	/** 기수 결산. 빅프 제거로 생성 경로가 없다(위 주석 참고). */
	COHORT_OUTCOME,

	/** 쓰는 화면 없음. DB CHECK 정합용. */
	COHORT_SUMMARY,

	/** 쓰는 화면 없음. DB CHECK 정합용. */
	CHECKPOINT
}
