package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * 매니저 한 명의 담당 반 전체 교체 요청(9차 Q3-①).
 *
 * <p>{@code UpdateClassroomManagersRequest}(반 하나 = 매니저 여럿)와 <b>방향만 반대</b>이고 규칙은 같다 —
 * 보낸 목록이 그대로 최종 상태가 된다.
 */
@Schema(description = """
		매니저 담당 반 전체 교체 요청.

		**보낸 목록이 그대로 최종 상태가 된다** — 부분 추가·삭제가 아니다.
		빈 배열을 보내면 담당을 전부 놓는다.""")
public record ReplaceManagerClassroomsRequest(

		@Schema(description = """
				최종 담당 반 ID 목록. 중복은 서버가 제거한다. **빈 배열 허용**(전체 해제).
				`null`은 허용하지 않는다 — 빈 배열(전체 해제)과 구분되지 않기 때문이다.""")
		@NotNull List<UUID> classroomIds
) {
	public ReplaceManagerClassroomsRequest {
		classroomIds = classroomIds == null ? List.of() : List.copyOf(classroomIds);
	}
}
