package com.bigproject.backend.domain.assessment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 다시 보기 개설 요청. <b>리포트 하나뿐이다.</b>
 *
 * <p>회차·1차 응시·다시 볼 문제는 전부 리포트에서 도출한다. 클라이언트가 회차나 문제 목록을 함께
 * 지목하게 두면 리포트가 말하는 것과 다른 조합이 만들어질 수 있고, 서버는 그것이 진짜 화면 상태인지
 * 알 방법이 없다 — 답변 제출이 문제·질문을 싣지 않는 것과 같은 이유다.
 */
@Schema(description = "다시 보기 개설")
public record ReviewOpenRequest(

		@Schema(description = """
				근거 리포트. `GET /assessment-rounds`의 `current.reportId`를 그대로 쓴다.
				본인 것이고 **발행된**(`published_at`이 있는) 리포트여야 한다.""",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull
		UUID reportId
) {
}
