package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

@Schema(description = """
		교육생 계정 상태 변경 요청.

		`status`는 **ACTIVE(활성화) 또는 INACTIVE(비활성화)만** 지정할 수 있다 — 그 외 값은 400이다.
		INVITED는 초대 흐름이 설정하는 값이라 이 API의 대상이 아니다.""")
public record UpdateTraineeStatusRequest(

		@NotNull
		AccountStatus status,

		@Schema(description = "변경 사유(감사 로그용, 선택)", example = "중도 이탈 처리", nullable = true)
		String reason
) {
	@AssertTrue(message = "교육생 계정 상태는 활성 또는 비활성만 직접 설정할 수 있습니다.")
	public boolean isMutableStatus() {
		return status == null || status == AccountStatus.ACTIVE || status == AccountStatus.INACTIVE;
	}
}
