package com.bigproject.backend.domain.usagemetering.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 기간 활동량 집계 포트. 목업 SA-02 ③ `사용 규모`의 완료 세션·채점 회차·발행 리포트와
 * OP-06 ⑤ 반별 표의 세션 수에 대응한다.
 *
 * <p><b>v07에서 구현 가능해졌다.</b> 06_MEAS(assessment_session·project_assessment_round)와
 * 10_RPT(report) 계열 테이블이 생기기 전에는 값을 만들 수 없어 0 고정이었다
 * (구 {@code OperationsSchemaPending}).
 *
 * <p>이 포트는 <b>스냅샷이 없을 때의 LIVE 경로 전용</b>이다. {@code organization_usage_snapshot}이
 * 있으면 그 값이 우선이며, 여기 쿼리는 실행되지 않는다.
 *
 * <p>세션·채점·리포트 테이블은 measurement·report 도메인 소유다. 남의 도메인 엔티티를 이쪽에서 매핑하면
 * 매핑이 두 벌로 갈라져 드리프트가 나므로, {@link OperationsCostRepository}와 같은 방식으로
 * <b>읽기 전용 SQL</b>로만 접근한다.
 */
public interface OperationsActivityRepository {

	/**
	 * 기간 내 완료된 세션 수. {@code assessment_session.status = 'COMPLETED'}이고
	 * {@code ended_at}이 기간에 들어오는 행을 센다 — 세션이 <b>끝난 시점</b>이 그 기간의 활동량이다.
	 */
	long countCompletedSessions(UUID organizationId, Instant from, Instant to);

	/**
	 * 기간 내 채점 회차 수.
	 *
	 * <p>⚠ {@code project_assessment_round}에는 "채점을 실행한 시각" 컬럼이 없다. 그래서
	 * <b>제출 마감({@code submission_due_at})이 기간에 들어온 회차</b>를 그 기간에 돌아간 회차로 센다 —
	 * 회차는 마감과 함께 채점으로 넘어가므로 개설 시각({@code created_at})보다 실제 실행 시점에 가깝다.
	 * 삭제된 회차({@code deleted_at})는 제외한다.
	 */
	long countGradingRounds(UUID organizationId, Instant from, Instant to);

	/**
	 * 기간 내 발행된 리포트 수. {@code report.published_at}이 기간에 들어온 행을 센다.
	 *
	 * <p>{@code lifecycle_status}로 거르지 않는다 — 나중에 SUPERSEDED가 된 리포트도
	 * <b>그 기간에 발행된 것은 사실</b>이고, 이 지표는 현재 유효한 리포트 수가 아니라 기간 활동량이다.
	 */
	long countPublishedReports(UUID organizationId, Instant from, Instant to);

	/**
	 * 선택 기수의 반별 완료 세션 수. 세션이 없는 반은 맵에 키가 없다(호출부에서 0으로 채운다).
	 *
	 * <p>{@code assessment_session}에는 반 정보가 없어 교육생의 반 배정을 타고 내려가야 한다:
	 * {@code assessment_session → measurement_attempt(cohort_id·user_id) → cohort_member → class_membership}.
	 * 배정이 해제된 행({@code class_membership.unassigned_at})은 제외한다.
	 */
	Map<UUID, Long> countCompletedSessionsByClass(
			UUID organizationId, UUID cohortId, Instant from, Instant to
	);
}
