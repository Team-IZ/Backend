package com.bigproject.backend.domain.reporting.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * report_snapshot.completion_status. DB CHECK(ck_report_snapshot_completion_status)와 1:1이다.
 *
 * <h2>🔴 같은 값이 리포트 종류에 따라 뜻이 다르다</h2>
 *
 * <p>{@code report_snapshot}은 교육생 리포트와 기수 리포트가 함께 쓰는 테이블이라
 * ({@code report.class_id}로 갈린다) 같은 컬럼에 성격이 다른 두 판정이 들어간다.
 *
 * <table>
 *   <caption>PARTIAL의 두 가지 뜻</caption>
 *   <tr><th>리포트</th><th>PARTIAL</th><th>성격</th></tr>
 *   <tr>
 *     <td>교육생(TR-04)</td>
 *     <td>문제 일부의 <b>AI 생성이 실패</b>해 개념 카드가 빠졌다</td>
 *     <td>시스템 장애</td>
 *   </tr>
 *   <tr>
 *     <td>기수(OP-05)</td>
 *     <td>미응시·무효·중단으로 <b>모수에서 정상 제외</b>된 응시가 있다</td>
 *     <td>정상 동작</td>
 *   </tr>
 * </table>
 *
 * <p><b>화면 문구를 공유하면 안 된다.</b> "일부 생성 실패"로 쓰면 OP-05가 정상 동작을 장애로
 * 표시하고, "일부 제외됨"으로 쓰면 TR-04에서 AI 실패가 정상적인 제외처럼 보인다.
 * DB 컬럼이 하나라 값을 나누는 건 스키마 변경이고, 그만한 일은 아니라고 판단했다
 * (2026-08-10 Reporting·Analytics 합의).
 *
 * <p>어느 쪽이든 <b>실패가 아니라 일부가 빈 채로 확정됐다</b>는 공통점은 같다.
 * PARTIAL이어도 발행은 되고, 화면에서 0으로 그리면 안 된다 —
 * "안 한 것"과 "못 읽은 것"은 다르다. Usage Metering의 {@code costComplete=false}와 같은 원칙이다.
 *
 * <p>기수 리포트는 {@code missing_count}가 몇 건이 빠졌는지 들고 있다.
 */
// 🔴 설명을 필드가 아니라 여기(타입)에 두는 이유:
//    OpenAPI 3.0은 $ref 옆 형제 필드를 무시하므로, 필드에 @Schema(description=)을 달면
//    둘 중 하나를 잃는다 — enumAsRef를 빼면 값 목록(FULL/PARTIAL)이 인라인 string으로
//    뭉개지고, 두면 필드 설명이 통째로 버려진다. 두 뜻은 어차피 이 컬럼의 성질이지
//    한 엔드포인트의 성질이 아니라 타입에 두는 편이 맞다.
//    (javadoc은 스펙에 닿지 않는다 — therapi 플러그인이 없어 @Schema만 실린다)
@Schema(
		name = "ReportCompletionStatus",
		description = """
				리포트 스냅샷 완전성. FULL이면 빠진 것이 없고, PARTIAL이면 일부가 빈 채로 \
				확정됐다 — PARTIAL이어도 발행은 된다. \
				PARTIAL의 원인은 리포트 종류마다 다르다: 교육생 리포트(TR-04)는 일부 문제의 \
				AI 생성이 실패해 개념 카드가 빠진 것(시스템 장애)이고, 기수 리포트(OP-05)는 \
				미응시·무효·중단으로 모수에서 정상 제외된 것(정상 동작)이다. \
				두 화면이 같은 안내 문구를 쓰면 한쪽이 정상 동작을 장애로 표시하게 되므로 \
				문구는 갈라 써야 한다.""",
		enumAsRef = true
)
public enum ReportCompletionStatus {

	/** 빠진 것이 없다. */
	FULL,

	/** 일부가 빠진 채 확정됐다. 무엇이 빠졌는지는 리포트 종류에 따라 다르다(클래스 javadoc). */
	PARTIAL
}
