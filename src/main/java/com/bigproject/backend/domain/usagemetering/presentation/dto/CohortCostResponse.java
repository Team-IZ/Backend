package com.bigproject.backend.domain.usagemetering.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * OP-06 ⑤ 비용 탭 응답. Frontend {@code src/features/operator/admin/_/api/types.ts}의
 * {@code getCost()} 반환 타입과 <b>필드명까지 1:1</b>이다 —
 * 프론트가 목({@code mockDb})에서 실서버로 바꿀 때 매핑 코드를 새로 쓰지 않게 하기 위함이다.
 *
 * <p><b>축이 SA-02 ③ 사용량과 다르다.</b> 저기는 "이번 달에 무엇을 얼마나 썼나"라 한 달 상세이고,
 * 여기는 "기수 동안 어느 달 어느 반이 튀었나"라 <b>월 × 반 매트릭스</b>다.
 * 그래서 {@code OrganizationUsageResponse}에 월 배열을 얹지 않고 엔드포인트를 나눴다.
 *
 * @param summary 상단 요약. 기관 전체와 선택 기수 두 범위가 섞여 있으므로 필드마다 범위를 명시했다.
 * @param classes 반별 행. 각 행이 월 칸을 갖는다.
 */
@Schema(description = "기수 비용 (OP-06 ⑤ 비용 탭)")
public record CohortCostResponse(

		@Schema(description = "기관 식별자")
		UUID organizationId,

		@Schema(description = "조회한 기수 식별자")
		UUID cohortId,

		@Schema(description = "통화 코드. 플랫폼 공통 USD", example = "USD")
		String currencyCode,

		CostSummary summary,

		@Schema(description = "반별 비용. 정렬은 요청의 sort가 정한다.")
		List<ClassCost> classes
) {

	/**
	 * 상단 요약.
	 *
	 * <p><b>범위가 둘이다.</b> {@code total}·{@code previousTotal}은 <b>기관 전체</b>,
	 * 나머지는 <b>선택 기수</b>다. 한 화면에 두 범위가 있으므로 화면도 제목에 그것을 쓴다.
	 */
	@Schema(description = "비용 요약")
	public record CostSummary(

			@Schema(description = "기준 월(yyyy-MM). 보통 이번 달", example = "2026-07")
			String month,

			@Schema(description = "**기관 전체** 기준 월 사용액")
			BigDecimal total,

			@Schema(description = """
					**기관 전체** 지난달 사용액. 증감만으로는 판단이 안 되므로 절대값을 함께 준다 —
					`+12%`가 `$400→$412`인지 `$50→$56`인지에 따라 할 일이 다르다.
					기준 월이 첫 달이면 null.""")
			BigDecimal previousTotal,

			@Schema(description = """
					전월 대비 증감**률(%)**. 부호를 그대로 쓴다(`+12` / `-8`).
					⚠️ 다른 API의 `changeRate`(0~1)와 단위가 다르다 — 화면 계약에 맞춘 값이다.
					지난달이 0이거나 없으면 0.""",
					example = "12.0")
			BigDecimal changePct,

			@Schema(description = "**선택 기수** 시작월부터 기준 월까지 누적 사용액. 예산 비율을 이 값으로 잰다.")
			BigDecimal cohortTotal,

			@Schema(description = """
					**기수 전체** 계약 예산. `organization_policy.monthly_ai_budget × 기수 개월 수`로 파생한다 —
					기수 단위 예산 컬럼이 스키마에 없어 월 예산에서 계산한다.
					활성 정책이 없거나 월 예산이 0이면 null이며, 그때 화면은 비율을 그리지 않는다.""")
			BigDecimal budget,

			@Schema(description = "기준 월 기준 기수 종료까지 남은 개월 수. 이미 종료됐으면 0", example = "2")
			int monthsLeft,

			@Schema(description = """
					**선택 기수** 월별 비용. **최근 달이 앞**이다(화면 월별 표는 최신이 위).
					아직 오지 않은 달은 담지 않는다.""")
			List<MonthlyCost> monthly,

			@Schema(description = """
					기준 월에 **실제로 비용이 발생한** 기수만. 평시에는 1건, 기수 전환기에는 2건이다.
					비용 0인 기수를 실으면 화면에 늘 여러 기수가 보인다.""")
			List<CohortCard> cohorts
	) {
	}

	/**
	 * 기수의 한 달치.
	 *
	 * <p>{@code projectNames}가 이 타입의 핵심이다 — 금액만 있으면 많은지 적은지 판단할 수 없는데,
	 * <b>비용은 세션에서 나오고 세션은 회차에서 나온다.</b> 회차가 둘인 달이 비싼 것은 정상이고,
	 * 하나인데 비싼 달이 조치 대상이다.
	 */
	@Schema(description = "기수 월별 비용 한 줄")
	public record MonthlyCost(

			@Schema(description = "월(yyyy-MM)", example = "2026-07")
			String month,

			BigDecimal amount,

			@Schema(description = "그달에 완료된 세션 수. **비용의 원인**이라 금액 옆에 둔다.")
			long sessions,

			@Schema(description = """
					그달에 제출 마감된 회차 이름. **비어 있으면 회차 없이 재시험만 있던 달**이다.""")
			List<String> projectNames
	) {
	}

	@Schema(description = "기수 카드")
	public record CohortCard(

			@Schema(description = "기수 식별자. 화면 계약이 `id`라 그대로 쓴다.")
			UUID id,

			String name,

			BigDecimal amount,

			@Schema(description = "현재 소속 교육생 수(나간 인원 제외). 1인당 비용은 화면이 만들지 않는다.")
			int trainees,

			@Schema(description = "기간 표기. 전환기에 두 기수가 뜰 때 왜 같이 있는지를 카드가 스스로 말한다.",
					example = "2026-03 ~ 09")
			String period
	) {
	}

	/**
	 * 반 한 줄. 월 칸이 <b>언제</b>를 말하고 {@code cohortAmount}가 <b>총량</b>을 말한다.
	 * 한 달치는 표본이 작아 우연히 갈리지만 누적은 크게 벌어진다.
	 */
	@Schema(description = "반별 비용 한 줄")
	public record ClassCost(

			UUID classId,

			@Schema(description = "이 반이 속한 기수")
			UUID cohortId,

			String className,

			@Schema(description = "반 담당 매니저 이름. 공동 담당이면 가장 먼저 배정된 1명. 없으면 null")
			String managerName,

			@Schema(description = "월별 비용. **오래된 달이 앞**이다(매트릭스는 왼쪽에서 오른쪽으로 시간이 흐른다).")
			List<MonthlyClassCost> monthly,

			@Schema(description = "기수 누적 사용액")
			BigDecimal cohortAmount,

			@Schema(description = "기수 누적 완료 세션 수. 배정이 해제된 교육생의 세션은 제외한다.")
			long cohortSessions
	) {
	}

	@Schema(description = "반 하나의 한 달치")
	public record MonthlyClassCost(

			@Schema(description = "월(yyyy-MM)", example = "2026-07")
			String month,

			BigDecimal amount
	) {
	}

	/** 반별 정렬. 월이 열로 펼쳐졌으므로 달마다 정렬을 만들지 않는다 — 그건 매트릭스가 이미 하는 일이다. */
	@Schema(description = "반별 정렬 기준")
	public enum ClassCostSort {

		/** 반 이름순. `A반 · B반 …`이 자연 순서다. */
		NAME,

		/** 기수 누적 많은 순. 한 달치는 표본이 작아 우연히 갈리므로 누적으로 정렬한다. */
		COHORT_AMOUNT
	}
}
