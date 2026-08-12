package com.bigproject.backend.domain.assessment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 힌트 열기 결과.
 *
 * <p>힌트는 <b>재진술</b>이다 — 질문을 다르게 말할 뿐 코드 위치·선택지·답의 방향을 주지 않는다.
 * 그래서 원 질문을 대체하지 않고 문구 하나만 돌려준다. 화면은 질문 아래에 덧붙인다.
 *
 * <p>점수는 깎이지 않는다. 남은 횟수를 내려보내는 것은 화면이 "2번 남음"을 그리기 위해서이지 불이익을
 * 알리기 위해서가 아니다.
 */
@Schema(description = "다시 설명(힌트) 결과")
public record HintResponse(
		UUID problemId,
		@Schema(description = "이 힌트가 붙는 축(L1~L4)") String axisCode,
		@Schema(description = "힌트 문구. 분석 시점에 동결된 것을 그대로 준다") String hintText,
		@Schema(description = "지금까지 쓴 힌트 수(1~2)") int hintsUsed,
		@Schema(description = "남은 횟수. 0이면 화면은 버튼을 문구로 바꾼다") int hintsLeft
) {
}
