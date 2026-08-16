package com.bigproject.backend.domain.intervention.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 브리프 저장 요청.
 *
 * <p>세 값 모두 <b>비어도 저장된다</b> — 화면이 추후 계획이 비었을 때 한 번만 확인하고 넘긴다.
 * 필수값으로 두면 "면담은 했는데 적을 게 없는" 경우를 저장할 수 없다.
 *
 * <h2>29차 R2 ① — 그래도 {@code causes}는 키를 보내야 한다</h2>
 *
 * <p>이 요청은 브리프를 <b>통째로 치환</b>한다. 그래서 "원인을 0건으로 저장한다"와 "원인 항목을
 * 이번 요청에서 뺐다"는 서로 다른 뜻인데, 키를 생략하면 그 둘이 구분되지 않는다 —
 * {@code ReplaceManagerClassroomsRequest.classroomIds}에서 같은 이유로 이미 내린 결정이다.
 *
 * <p>{@code causes}만 {@code required}이고 0건이면 {@code []}를 보낸다. 나머지 둘은 자유 서술이라
 * 없는 것과 빈 문자열이 같은 뜻이므로 선택으로 둔다.
 *
 * <p>지금은 키가 빠져도 서버가 빈 목록으로 받는다(아래 압축 생성자). 스펙은 보내라고 적고
 * 서버는 관대한 쪽인데, 반대로 두면 이미 배포된 화면이 깨진다.
 */
@Schema(description = "면담 브리프 저장 요청")
public record SaveInterviewBriefRequest(

		@Schema(description = """
				고른 원인 분류. 복수 선택이고 **0건도 허용**합니다 — 그때는 `[]`를 보내세요.

				`CONCEPT_GAP`(개념 이해 부족) · `OUT_OF_SCOPE`(담당 범위 밖) ·
				`TIME_SHORTAGE`(구현 시간 부족) · `EXPRESSION`(설명·표현 어려움) ·
				`DIFFICULTY_UP`(난이도 상승) · `TEAM_DEPENDENCE`(팀 의존) · `CONDITION`(컨디션·심리)

				⚠️ 원인에서 파생되는 **조치(라우팅 목적지)는 보내지 않습니다** — 화면이 계산합니다.
				""", example = "[\"CONCEPT_GAP\", \"TEAM_DEPENDENCE\"]",
				requiredMode = Schema.RequiredMode.REQUIRED)
		List<String> causes,

		@Schema(description = "상세 사유. 매니저가 타이핑한 서술. **비워도 저장됩니다**",
				example = "담당 범위가 좁아 전체 흐름을 볼 기회가 없었다고 함")
		String why,

		@Schema(description = "추후 계획. **비워도 저장됩니다**", example = "담당 기능 흐름 그려오기")
		String nextAction) {

	public SaveInterviewBriefRequest {
		causes = causes == null ? List.of() : List.copyOf(causes);
	}
}
