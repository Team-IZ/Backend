package com.bigproject.backend.domain.usagemetering.presentation.dto;

import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * 기관 월별 사용량. 목업 SA-02 ③ 사용량·AI 비용(슈퍼어드민)과 OP-06 ⑤ 비용(오퍼레이터)을 함께 채운다.
 *
 * <p>슈퍼어드민은 storage/activity/aiCost 전체를 보고, 오퍼레이터는 aiCost와 cohortCosts/classCosts를 본다.
 * 매니저에게는 비용을 노출하지 않는다(목업: "보이면 '비싸니까 세션 짧게'라는 잘못된 압력이 생긴다").
 *
 * <p>v06에서 {@code ai_usage}에 기수·반 귀속 컬럼이 생겨 기수별·반별 비용이 실제 값으로 채워지고,
 * 단가 미설정(UNPRICED) 호출을 비용 합계에서 제외한 뒤 <b>몇 건이 빠졌는지</b> 함께 알려준다.
 */
@Schema(description = "기관 월별 저장량·활동·AI 비용")
public record OrganizationUsageResponse(
		UUID organizationId,
		YearMonth period,
		String currencyCode,

		@Schema(description = """
				이 응답이 미리 집계된 스냅샷에서 왔는지, 원장을 즉시 집계한 값인지.
				SNAPSHOT이면 organization_usage_snapshot 기준이고, LIVE면 ai_usage를 그 자리에서 합산한 값이다.""")
		AggregationSource aggregationSource,

		StorageUsage storage,
		ActivityUsage activity,
		AiCostUsage aiCost,

		@Schema(description = "기수별 비용 (목업 OP-06 ⑤ `7기 $268 · 250명 · 1인당 $1.07`)")
		List<CohortCostUsage> cohortCosts,

		@Schema(description = """
				반별 비용 (목업 OP-06 ⑤ `A반 · 박지현 · 25명 · 69세션 · $29`).
				반 표는 항상 "선택 기수" 범위이므로 cohortId를 지정하지 않으면 빈 목록이다.""")
		List<ClassCostUsage> classCosts
) {

	/** 집계 출처. 스냅샷 배치가 아직 없는 환경에서는 LIVE로 떨어진다. */
	public enum AggregationSource {
		SNAPSHOT, LIVE
	}

	@Schema(description = "저장량 구성. 목업 `저장량 구성 총 9.4 GB`")
	public record StorageUsage(
			@Schema(description = """
					기관 총 저장량. 원천이 ORG_TOTAL 행을 제공하면 그 값을 그대로 쓰고,
					없으면 세부 카테고리 합으로 계산한다 — 둘을 함께 더하면 이중 계산된다.""")
			long totalBytes,

			@Schema(description = "코드 제출물 (레포·ZIP)")
			long codeSubmissionBytes,

			@Schema(description = "문답 원문")
			long sessionLogBytes,

			@Schema(description = "채점 근거 (evidence)")
			long gradingEvidenceBytes,

			@Schema(description = "리포트 · 내보내기 PDF")
			long reportBytes,

			@Schema(description = "전월 대비 증감률. 전월 값이 0이거나 없으면 null", example = "0.08")
			BigDecimal changeRateVsPrevMonth
	) {
	}

	@Schema(description = "사용 규모. 목업 `활성 교육생 148 / 완료 세션 612 / 채점 회차 1,840 / 발행 리포트 96`")
	public record ActivityUsage(
			int activeTrainees,

			@Schema(description = "⚠ 세션 테이블(06_MEAS)이 아직 없어 스냅샷에 값이 없으면 0입니다.")
			long completedSessions,

			@Schema(description = "⚠ 채점 테이블(06_MEAS)이 아직 없어 스냅샷에 값이 없으면 0입니다.")
			long gradingRounds,

			@Schema(description = "⚠ 리포트 테이블(10_RPT)이 아직 없어 스냅샷에 값이 없으면 0입니다.")
			long generatedReports
	) {
	}

	@Schema(description = "AI 비용. 목업 `AI 비용 · 모델별 · 이번 달 $412 / 예산 $600 · 전월 +12%`")
	public record AiCostUsage(
			@Schema(description = "단가가 설정된 호출만 합산한 비용. 단가 미설정 호출은 0으로 더하지 않고 제외한다.")
			BigDecimal totalCost,

			BigDecimal monthlyBudget,

			@Schema(description = "예산 소진율(0~1). 예산이 0이면 null", example = "0.6867")
			BigDecimal budgetUsageRate,

			@Schema(description = "예산 초과 여부. 초과해도 서비스 중단은 아니며 `예산 초과` 배지만 붙는다.")
			boolean budgetExceeded,

			@Schema(description = "전월 대비 증감률. 전월 값이 0이거나 없으면 null", example = "0.12")
			BigDecimal changeRateVsPrevMonth,

			@Schema(description = """
					비용 합계가 모든 호출을 포함하는지. false면 단가 미설정 호출이 섞여 있어
					실제 청구액이 이 값보다 크다 — 화면은 `일부 단가 미설정`을 함께 표시해야 한다.""")
			boolean costComplete,

			@Schema(description = "단가 미설정이라 비용 합계에서 빠진 호출 수. 0이면 합계가 완전하다.")
			long unpricedCallCount,

			@Schema(description = "모델별 내역의 합계 행")
			UsageTotal total,

			@Schema(description = "(용도, 모델) 조합별 내역")
			List<ModelUsage> models
	) {
	}

	@Schema(description = "모델별 사용 내역 한 줄")
	public record ModelUsage(
			@Schema(description = """
					용도. ANSWER_GRADING(답변 채점) / QUESTION_GENERATION(질문 생성) / SUMMARY_DRAFT(요약) /
					CURRICULUM_ANALYSIS(교안 분석) / CODE_ANALYSIS(코드 분석)""",
					example = "ANSWER_GRADING")
			String usageType,

			@Schema(description = """
					호출 시점에 기관이 선택했던 모델 티어 스냅샷. 목업 `정확도 우선` / `균형` / `비용 우선`.
					채점처럼 플랫폼이 모델을 고정하는 기능은 티어가 없어 null이다(화면 `플랫폼 고정`).""")
			AiTier tier,

			@Schema(description = "모델 표시명", example = "claude-opus-5")
			String model,

			long calls,
			long inputTokens,
			long outputTokens,

			@Schema(description = "100만 토큰당 입력 단가. 단가 미설정이면 null")
			BigDecimal inputPricePerMillionTokens,

			@Schema(description = "100만 토큰당 출력 단가. 단가 미설정이면 null")
			BigDecimal outputPricePerMillionTokens,

			@Schema(description = """
					단가 미설정 여부. true면 비용을 0으로 계산하지 않고 합계에서 제외한다
					(목업: "0으로 계산하면 청구액이 실제보다 작아 보인다").""")
			boolean pricingMissing,

			@Schema(description = "이 행의 비용. 단가 미설정이면 null")
			BigDecimal cost
	) {
	}

	@Schema(description = "모델별 내역 합계. 목업 `합계 5,080 / 29.7M / 6.1M / $412`")
	public record UsageTotal(
			long calls,
			long inputTokens,
			long outputTokens,
			BigDecimal cost
	) {
	}

	@Schema(description = "기수별 비용")
	public record CohortCostUsage(
			UUID cohortId,
			String name,
			int traineeCount,
			BigDecimal cost,

			@Schema(description = "교육생 1인당 비용. 교육생이 0명이면 null")
			BigDecimal costPerTrainee,

			@Schema(description = "단가 미설정이라 이 기수 비용에서 빠진 호출 수")
			long unpricedCallCount
	) {
	}

	@Schema(description = "반별 비용")
	public record ClassCostUsage(
			UUID classId,
			String name,

			@Schema(description = "반 담당 매니저 이름. 담당이 없으면 null(목업 `담당 없음`)")
			String managerName,

			int traineeCount,

			@Schema(description = "⚠ 세션 테이블(06_MEAS)이 아직 없어 항상 0입니다.")
			long sessionCount,

			BigDecimal cost,

			@Schema(description = "단가 미설정이라 이 반 비용에서 빠진 호출 수")
			long unpricedCallCount
	) {
	}
}
