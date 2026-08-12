package com.bigproject.backend.domain.assessment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 답변 제출 요청. <b>답변 원문 하나뿐이다.</b>
 *
 * <p>어느 문제의 어느 질문에 답하는지는 싣지 않는다 — 진행 위치는 서버의 커서가 정본이다. 클라이언트가
 * 지목하게 두면 계단을 건너뛰거나 이미 닫힌 문제에 답을 붙이는 요청이 만들어지고, 서버는 그것이 진짜
 * 화면 상태인지 알 방법이 없다.
 *
 * <p>길이 하한을 두지 않는다. 정의서 §6 — 짧은 답변은 알리되 막지 않는다("강제하면 의미 없는 글자를
 * 채운다"). 15자 미만 안내는 화면이 하고 서버는 그대로 받는다.
 */
@Schema(description = "답변 제출")
public record AnswerSubmitRequest(
		@Schema(description = "학생이 쓴 답변 원문", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank
		String answerText
) {
}
