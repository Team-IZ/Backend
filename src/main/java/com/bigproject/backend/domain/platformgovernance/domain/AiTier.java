package com.bigproject.backend.domain.platformgovernance.domain;

/**
 * 기관이 선택하는 AI 모델 티어.
 *
 * <p>DB CHECK(organization_policy.question_generation_tier_code / summary_tier_code,
 * platform_ai_tier_model_policy.tier_code, ai_usage.tier_code): IN ('ACCURACY_FIRST','BALANCED','COST_FIRST').
 *
 * <p>기관은 <b>티어 이름만</b> 고르고 실제 논리 모델은 플랫폼 정책(platform_ai_tier_model_policy)이 결정한다 —
 * 목업 OP-06 §7: "모델별 단가는 SA-03(플랫폼이 정하고 기관은 티어 이름만 본다)".
 * 채점(ANSWER_GRADING)은 티어 선택 대상이 아니라 플랫폼 고정이므로 이 값을 쓰지 않는다.
 */
public enum AiTier {
	ACCURACY_FIRST,
	BALANCED,
	COST_FIRST
}
