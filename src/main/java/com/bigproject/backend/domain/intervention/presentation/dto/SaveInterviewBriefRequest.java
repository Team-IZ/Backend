package com.bigproject.backend.domain.intervention.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 브리프 저장 요청.
 *
 * <p>세 값 모두 비어도 저장된다 — 화면이 추후 계획이 비었을 때 한 번만 확인하고 넘긴다.
 * 필수값으로 두면 "면담은 했는데 적을 게 없는" 경우를 저장할 수 없다.
 */
@Schema(description = "면담 브리프 저장 요청")
public record SaveInterviewBriefRequest(

		@Schema(description = """
				고른 원인 분류. 복수 선택이고 **0건도 허용**합니다.

				`CONCEPT_GAP`(개념 이해 부족) · `OUT_OF_SCOPE`(담당 범위 밖) ·
				`TIME_SHORTAGE`(구현 시간 부족) · `EXPRESSION`(설명·표현 어려움) ·
				`DIFFICULTY_UP`(난이도 상승) · `TEAM_DEPENDENCE`(팀 의존) · `CONDITION`(컨디션·심리)

				⚠️ 원인에서 파생되는 **조치(라우팅 목적지)는 보내지 않습니다** — 화면이 계산합니다.
				""", example = "[\"CONCEPT_GAP\", \"TEAM_DEPENDENCE\"]")
		List<String> causes,

		@Schema(description = "상세 사유. 매니저가 타이핑한 서술", example = "담당 범위가 좁아 전체 흐름을 볼 기회가 없었다고 함")
		String why,

		@Schema(description = "추후 계획. **비워도 저장됩니다**", example = "담당 기능 흐름 그려오기")
		String nextAction) {
}
