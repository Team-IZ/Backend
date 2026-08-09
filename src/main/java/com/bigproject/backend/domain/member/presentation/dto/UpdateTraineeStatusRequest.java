package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.TraineeStatusUpdate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = """
		교육생 계정 상태 변경 요청.

		`status`는 **ACTIVE(활성화) 또는 INACTIVE(비활성화)만** 지정할 수 있다. INVITED는 초대 흐름이
		설정하는 값이라 이 API의 대상이 아니며, 타입 자체가 두 값만 받으므로 그 외 값은 400이다.""")
public record UpdateTraineeStatusRequest(

		@Schema(description = "변경할 계정 상태", example = "INACTIVE", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull
		TraineeStatusUpdate status,

		@Schema(description = "변경 사유(감사 로그용, 선택)", example = "중도 이탈", nullable = true)
		String reason
) {
}
