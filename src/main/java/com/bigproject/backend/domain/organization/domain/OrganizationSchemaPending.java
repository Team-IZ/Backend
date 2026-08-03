package com.bigproject.backend.domain.organization.domain;

/**
 * v2 IA 목업(SA-01 기관 목록 / SA-02 기관 상세)이 요구하지만 <b>아직 DB 테이블이 없어 채울 수 없는 값</b>의 기본값.
 *
 * <p>application.yaml의 {@code spring.jpa.hibernate.ddl-auto: validate} 때문에 엔티티에 없는 컬럼을 매핑하면
 * 앱 부팅 자체가 실패한다. 그래서 채울 수 없는 값은 전부 이 클래스를 거치게 했다.
 *
 * <p><b>v06(테이블정의서 목업O)에서 대부분 해소됐다.</b> slug · display_code · email_domain은 organization에
 * 컬럼이 생겨 실제 저장·응답으로 바뀌었고, 이 클래스에서 제거했다. 남은 것은 도메인 자체가 아직 없는 값뿐이다.
 *
 * <p>남은 대기 항목:
 * <table>
 *   <tr><th>목업 필드</th><th>필요한 것</th><th>현재 상태</th></tr>
 *   <tr><td>활성 세션 수</td><td>06_MEAS 세션 계열 테이블</td><td>테이블 없음 → 0</td></tr>
 * </table>
 *
 * @see com.bigproject.backend.domain.usagemetering.domain.OperationsSchemaPending
 */
public final class OrganizationSchemaPending {

	/**
	 * TODO(schema-align): 06_MEAS 세션 테이블이 생기면 진행 중 세션 수를 집계한다.
	 * 이번 DDL 범위(01_SYS·02_AUTH·03_ORG·05_CUR)에는 session/submission/grading/report 계열 테이블이 없다.
	 */
	public static final int ACTIVE_SESSION_COUNT = 0;

	private OrganizationSchemaPending() {
	}
}
