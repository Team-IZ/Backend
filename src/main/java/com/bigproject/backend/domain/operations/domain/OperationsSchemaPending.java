package com.bigproject.backend.domain.operations.domain;

/**
 * v2 IA 목업(SA-02 ③ 사용량·AI 비용 / SA-02 ④ 설정 / OP-06 ⑤ 비용)이 요구하지만
 * <b>아직 DB 테이블이 없어 채울 수 없는 값</b>의 기본값.
 *
 * <p><b>v06(테이블정의서 목업O)에서 대부분 해소됐다.</b> 다음 항목은 실제 컬럼으로 대체돼 이 클래스에서 제거했다.
 * <table>
 *   <tr><th>이전 상수</th><th>대체된 컬럼</th></tr>
 *   <tr><td>MODEL_TIER</td><td>{@code ai_usage.tier_code} (호출 시점 티어 스냅샷)</td></tr>
 *   <tr><td>PRICING_MISSING</td><td>{@code ai_usage.pricing_status} + 단가 컬럼 NULL 허용</td></tr>
 *   <tr><td>MONTHLY_TOKEN_LIMIT</td><td>{@code organization_policy.monthly_token_limit}</td></tr>
 *   <tr><td>GITHUB_ORG_INTEGRATION_ENABLED</td><td>{@code organization_policy.allow_github_integration}</td></tr>
 *   <tr><td>ZIP_UPLOAD_ENABLED</td><td>{@code organization_policy.allow_zip_submission}</td></tr>
 *   <tr><td>CONTRIBUTION_ANALYSIS_ENABLED</td><td>{@code organization_policy.enable_big_project_contribution_analysis}</td></tr>
 *   <tr><td>UNPRICED_COST</td><td>단가 미설정 호출은 비용을 0이 아니라 <b>null</b>로 두고 합계에서 제외한다</td></tr>
 * </table>
 * 기수별·반별 비용도 {@code ai_usage.cohort_id}·{@code class_id}가 생겨 빈 목록 대신 실제 집계로 바뀌었다.
 *
 * <p>남은 대기 항목은 <b>도메인 테이블 자체가 이번 DDL 범위 밖</b>인 3지표뿐이다.
 * 값이 0인 것과 "아직 못 만든 것"을 구분해야 하므로 상수로 남겨 둔다.
 *
 * @see com.bigproject.backend.domain.organization.domain.OrganizationSchemaPending
 */
public final class OperationsSchemaPending {

	/** TODO(schema-align): 06_MEAS 세션 테이블이 생기면 완료 세션 수를 집계한다. */
	public static final long COMPLETED_SESSIONS = 0L;

	/** TODO(schema-align): 06_MEAS 채점 테이블이 생기면 채점 회차를 집계한다. */
	public static final long GRADING_ROUNDS = 0L;

	/** TODO(schema-align): 10_RPT 리포트 테이블이 생기면 발행 리포트 수를 집계한다. */
	public static final long GENERATED_REPORTS = 0L;

	private OperationsSchemaPending() {
	}
}
