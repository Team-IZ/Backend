package com.bigproject.backend.domain.usagemetering.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * OP-06 ⑤ 비용 탭 전용 조회. {@link OperationsCostRepository}가 <b>한 달치 합계</b>를 주는 것과 달리
 * 여기는 <b>월을 축으로 펼친 값</b>을 준다.
 *
 * <p><b>왜 나눴나.</b> SA-02 ③ 사용량 탭은 "이번 달에 무엇을 얼마나 썼나"라 한 달 상세가 필요하고,
 * OP-06 ⑤는 "기수 동안 어느 달 어느 반이 튀었나"라 여러 달 추이가 필요하다. 축이 다르므로
 * 한 응답에 합치면 양쪽 다 안 쓰는 데이터를 매번 받게 된다.
 *
 * <p>모든 금액은 <b>단가가 설정된 호출만</b> 합산한다({@code pricing_status <> 'UNPRICED'}).
 * 0으로 더하면 청구액이 실제보다 작아 보이기 때문이다.
 */
public interface CohortCostRepository {

	/** 기수 기본 정보. 월 범위와 {@code monthsLeft}·{@code period} 표기의 근거다. */
	CohortPeriod findCohortPeriod(UUID organizationId, UUID cohortId);

	/** 기관 전체 월별 비용. 요약의 {@code total}·{@code previousTotal} 계산에 쓴다. */
	List<MonthlyAmount> findOrganizationMonthlyCost(UUID organizationId, Instant from, Instant to);

	/** 선택 기수의 월별 비용과 완료 세션 수. */
	List<MonthlyCohortCost> findCohortMonthlyCost(UUID organizationId, UUID cohortId, Instant from, Instant to);

	/**
	 * 월별로 그달에 <b>제출 마감된</b> 회차 이름. 비용의 원인을 설명하는 값이라
	 * 금액과 같은 행에 실린다 — 회차가 둘인 달이 비싼 것은 정상이고, 하나인데 비싼 달이 조치 대상이다.
	 */
	List<MonthlyRoundName> findCohortMonthlyRoundNames(UUID organizationId, UUID cohortId, Instant from, Instant to);

	/**
	 * 기준 월에 <b>실제로 비용이 발생한</b> 기수만. 평시에는 하나, 기수 전환기에는 둘이다.
	 * 비용이 0인 기수를 실으면 화면에 늘 여러 기수가 보인다.
	 */
	List<CohortCard> findActiveCohortCards(UUID organizationId, Instant from, Instant to);

	/** 반 메타와 기수 누적. 누적은 월 범위 전체를 합산한 값이다. */
	List<ClassSummary> findClassSummaries(UUID organizationId, UUID cohortId, Instant from, Instant to);

	/** 반 × 월 비용. 비용이 0인 (반, 월) 조합은 행이 없으므로 호출부가 0으로 채운다. */
	List<MonthlyClassAmount> findClassMonthlyCost(UUID organizationId, UUID cohortId, Instant from, Instant to);

	record CohortPeriod(UUID cohortId, String name, LocalDate startDate, LocalDate endDate) {
	}

	record MonthlyAmount(YearMonth month, BigDecimal amount) {
	}

	record MonthlyCohortCost(YearMonth month, BigDecimal amount, long sessions) {
	}

	record MonthlyRoundName(YearMonth month, String roundName) {
	}

	record CohortCard(UUID cohortId, String name, BigDecimal amount, int traineeCount,
			LocalDate startDate, LocalDate endDate) {
	}

	record ClassSummary(UUID classId, String name, String managerName,
			BigDecimal cohortAmount, long cohortSessions) {
	}

	record MonthlyClassAmount(UUID classId, YearMonth month, BigDecimal amount) {
	}
}
