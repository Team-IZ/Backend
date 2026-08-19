package com.bigproject.backend.domain.organization.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 기관이 정해 두는 <b>기수 결과 공개 범위</b>.
 * DB CHECK({@code organization_policy.default_disclosure_scope}): IN ('FULL','SUMMARY','PRIVATE').
 *
 * <h2>🔴 지금은 리포트에 반영되지 않는다</h2>
 *
 * <p>리포트 공개/비공개 개념이 폐지되면서(2026-08-19) <b>이 값이 교육생 리포트 열람에 미치는
 * 영향은 없다.</b> 리포트는 발행되는 순간 전문이 열리고, 문제 단위 가림막은 다시 보기 진행
 * 상태 하나뿐이다({@code TraineeReportServiceImpl}).
 *
 * <p>그래도 남겨 두는 것은 {@code organization_policy.default_disclosure_scope}와
 * {@code cohort.disclosure_scope}가 둘 다 NOT NULL이고, 기관 설정 화면(OP·슈퍼어드민)이
 * 이 값을 읽고 쓰기 때문이다. 리포트 도메인이 아니라 기관 정책의 값이라 여기에 둔다 —
 * 종전 위치였던 {@code domain/disclosure} 패키지는 리포트 공개 도메인이라 함께 없어졌다.
 */
@Schema(name = "DisclosureScope",
		description = "기관의 기수 결과 공개 범위 기본값. SUMMARY(요약만) · PRIVATE(비공개) · FULL(전문). "
				+ "⚠️ 교육생 리포트 열람에는 반영되지 않는다 — 리포트는 발행 즉시 전문이 열린다.",
		enumAsRef = true)
public enum DisclosureScope {
	SUMMARY,
	PRIVATE,
	FULL
}
