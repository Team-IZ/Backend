package com.bigproject.backend.domain.operations.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 기수·반 단위 AI 비용 집계 포트. 목업 OP-06 ⑤ 비용 탭의 두 표에 대응한다.
 *
 * <p>v06에서 {@code ai_usage}에 {@code cohort_id}·{@code class_id}가 생기면서 비로소 구현할 수 있게 됐다
 * (이전에는 컬럼이 없어 빈 목록으로 대체하고 있었다).
 *
 * <p>기수·반의 이름과 인원, 담당 매니저는 cohort·classroom 도메인 소유의 테이블에 있다.
 * 남의 도메인 엔티티를 이쪽에서 매핑하면 매핑이 두 벌로 갈라져 드리프트가 나므로,
 * organization 도메인의 통계 포트와 같은 방식으로 <b>읽기 전용 SQL</b>로만 접근한다.
 */
public interface OperationsCostRepository {

	/** 기수별 비용. cohortId가 null이면 기관의 모든 기수를 돌려준다. */
	List<CohortCost> findCohortCosts(UUID organizationId, UUID cohortId, Instant from, Instant to);

	/** 선택 기수의 반별 비용. 목업상 반 표는 항상 "지금 고른 기수" 범위다. */
	List<ClassCost> findClassCosts(UUID organizationId, UUID cohortId, Instant from, Instant to);

	/**
	 * @param cost 단가가 설정된 호출만 합산한 비용
	 * @param unpricedCallCount 단가 미설정이라 비용에서 빠진 호출 수. 0보다 크면 이 값은 실제보다 작다.
	 */
	record CohortCost(UUID cohortId, String name, int traineeCount, BigDecimal cost, long unpricedCallCount) {
	}

	/**
	 * @param managerName 반 담당 매니저 이름. 배정이 없으면 null(목업의 `담당 없음`)
	 */
	record ClassCost(
			UUID classId,
			String name,
			String managerName,
			int traineeCount,
			BigDecimal cost,
			long unpricedCallCount
	) {
	}
}
