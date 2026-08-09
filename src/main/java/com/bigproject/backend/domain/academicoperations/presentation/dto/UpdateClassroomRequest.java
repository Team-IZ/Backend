package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

/**
 * 반 수정 요청(9차 R6). <b>부분 수정</b>이라 보낸 필드만 바뀐다.
 *
 * <p>{@code required}를 두지 않은 것은 의도한 것이다 — 이름만 고치는 모달과 정원만 고치는 모달이
 * 따로 있는데, 둘 다 필수로 두면 각자 안 바꾸는 값을 매번 실어 보내야 하고 그 순간
 * 다른 사람이 바꾼 값을 되돌리는 경로가 열린다.
 *
 * <p>다만 <b>둘 다 비면 400</b>이다({@code CLASSROOM_UPDATE_EMPTY}) — 아무 일도 하지 않는 요청이
 * 200으로 돌아오면 화면은 저장됐다고 오해한다.
 */
@Schema(description = """
		반 수정 요청. **보낸 필드만 바뀐다.**
		둘 다 생략하면 400 `CLASSROOM_UPDATE_EMPTY`다.""")
public record UpdateClassroomRequest(

		@Schema(description = """
				새 반 이름. 같은 기수 안에서 중복되면 409 —— 단 **자기 자신은 빼고** 판정하므로
				이름을 그대로 두고 다시 보내도 409가 아니다. 생략하면 이름을 바꾸지 않는다.""",
				example = "1반", nullable = true)
		String name,

		@Schema(description = """
				새 정원. 1 이상. 생략하면 정원을 바꾸지 않는다.
				**현재 인원보다 작게 두는 것을 막지 않는다** — 정원 초과는 배정 경로가 이미 허용하는
				상태라(중도 합류·반 통폐합) 여기서만 막으면 규칙이 두 벌이 된다.""",
				example = "30", nullable = true)
		@Min(1) Integer capacity
) {
	public UpdateClassroomRequest {
		// 공백만 보낸 것은 "안 바꾼다"와 같게 본다. 빈 이름으로 덮어써 반 이름이 사라지는 것을 막는다.
		name = (name == null || name.isBlank()) ? null : name;
	}
}
