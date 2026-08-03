package com.bigproject.backend.domain.platformgovernance.presentation.dto;

import com.bigproject.backend.domain.platformgovernance.domain.AiTier;
import com.bigproject.backend.domain.platformgovernance.domain.PlatformAiTierModelPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 티어 ↔ 모델 매핑 변경 요청. 목업 SA-03 ① `질문 생성 · 정확도 우선 / 균형 / 비용 우선` 3티어 매핑.
 *
 * <p>채점 모델과 달리 <b>재캘리브레이션이 필요 없다.</b> 질문 생성·요약은 점수가 아니라 산출물이라
 * 버전 간 비교 문제가 생기지 않는다. 그래서 확인 모달도 요구하지 않는다.
 */
@Schema(description = "티어 ↔ 모델 매핑 변경 요청")
public record UpdateTierModelRequest(

		@Schema(description = "적용 기능. QUESTION_GENERATION 또는 SUMMARY_DRAFT", example = "QUESTION_GENERATION")
		@NotNull
		PlatformAiTierModelPolicy.FeatureCode featureCode,

		@Schema(description = "티어. ACCURACY_FIRST / BALANCED / COST_FIRST", example = "BALANCED")
		@NotNull
		AiTier tierCode,

		@Schema(description = "이 (기능, 티어)가 사용할 ai_model.model_id")
		@NotNull
		UUID modelId,

		@Schema(description = "변경 사유", example = "비용 최적화")
		String changeReason
) {
}
