package com.bigproject.backend.domain.platformgovernance.presentation.dto;

import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * 슈퍼어드민 계정 상태 변경 요청. 목업 SA-03 ② 표의 행별 액션 `정지` / `재활성`.
 *
 * <p>임의 상태로 바꾸지 못하게 ACTIVE(재활성)와 INACTIVE(정지)만 허용한다.
 * PENDING은 초대 흐름이 설정하는 값이다.
 */
@Schema(description = """
		슈퍼어드민 계정 상태 변경 요청.

		`status`는 **ACTIVE(재활성) 또는 INACTIVE(정지)만** 지정할 수 있다 — 그 외 값은 400이다.
		PENDING은 초대 흐름이 설정한다.""")
public record UpdateOperatorStatusForPlatformRequest(

		@NotNull
		OperatorAccountStatus status,

		@Schema(description = "변경 사유(감사 로그용, 선택)", example = "퇴사 처리", nullable = true)
		String reason
) {
	@JsonIgnore
	@AssertTrue(message = "슈퍼어드민 계정 상태는 활성 또는 정지만 직접 설정할 수 있습니다.")
	public boolean isMutableStatus() {
		return status == null
				|| status == OperatorAccountStatus.ACTIVE
				|| status == OperatorAccountStatus.INACTIVE;
	}
}
