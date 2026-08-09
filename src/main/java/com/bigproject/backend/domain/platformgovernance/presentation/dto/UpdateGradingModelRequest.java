package com.bigproject.backend.domain.platformgovernance.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 채점 모델 변경 요청. 목업 SA-03 ① `채점 · 고정 · claude-x` 행의 변경 액션.
 *
 * <p><b>되돌릴 수 없는 변경이다.</b> 목업:
 * "채점 모델을 바꾸면 <b>전 기관 재캘리브레이션</b>이 필요하고, 버전이 다른 결과끼리는 화면에서 비교를 막는다."
 *
 * <p>그래서 화면의 확인 모달을 서버에서도 한 번 더 요구한다 — {@code acknowledgeRecalibration}을
 * 명시적으로 true로 보내지 않으면 400으로 거절한다. 버튼 한 번에 전 기관 점수 비교가 끊기면 안 된다.
 */
@Schema(description = "채점 모델 변경 요청 (전 기관 재캘리브레이션 유발)")
public record UpdateGradingModelRequest(

		@Schema(description = "새 채점 논리 모델의 ai_model.model_id")
		@NotNull
		UUID modelId,

		@Schema(description = """
				새로 만들 캘리브레이션 버전 코드. 화면·결과 비교에서 이 코드로 버전을 구분한다.
				영문 대문자로 시작하고 대문자·숫자·밑줄만 쓸 수 있다.""",
				example = "CAL_2026_08_V1")
		@NotNull
		@Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "버전 코드는 영문 대문자로 시작하고 대문자·숫자·밑줄만 사용할 수 있습니다.")
		@Size(max = 100)
		String calibrationVersionCode,

		@Schema(description = "변경 사유. 감사·이력 확인용", example = "claude-opus-5 로 상향", nullable = true)
		String changeReason,

		@Schema(description = """
				전 기관 재캘리브레이션이 발생하고 되돌릴 수 없음을 확인했다는 표시.
				**true가 아니면 400으로 거절한다** — 화면 확인 모달을 우회한 호출을 막기 위한 안전장치다.""",
				example = "true")
		@NotNull
		Boolean acknowledgeRecalibration
) {
	@AssertTrue(message = "전 기관 재캘리브레이션 발생을 확인해야 채점 모델을 변경할 수 있습니다.")
	public boolean isRecalibrationAcknowledged() {
		return Boolean.TRUE.equals(acknowledgeRecalibration);
	}
}
