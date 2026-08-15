package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * 매니저 계정 상태 변경 요청. OP-06 `매니저` 탭 표의 행별 액션 `정지` / `재활성`에 대응한다.
 *
 * <p>오퍼레이터의 {@code UpdateOperatorStatusRequest}와 같은 모양이다 — 다른 것은 상태 enum이
 * {@code AccountStatus}(매니저·교육생 축)라는 점뿐이다.
 *
 * <p>임의 상태로 바꾸지 못하게 ACTIVE(재활성)와 INACTIVE(정지)만 허용한다.
 * {@code INVITED}는 초대 흐름이 설정하는 값이라 여기서 지정할 수 없다.
 * ({@code LOCKED}는 9차 Q3-②로 {@link com.bigproject.backend.domain.member.domain.AccountStatus}에서 제거했다.)
 */
@Schema(description = """
		매니저 계정 상태 변경 요청.

		`status`는 **ACTIVE(재활성) 또는 INACTIVE(정지)만** 지정할 수 있다 — 그 외 값은 400이다.
		INVITED는 초대 흐름이 설정하는 값이라 지정할 수 없다.""")
public record UpdateManagerStatusRequest(

		@NotNull
		AccountStatus status,

		@Schema(description = "변경 사유(감사 로그용, 선택)", example = "퇴사 처리", nullable = true)
		String reason
) {
	@JsonIgnore
	@AssertTrue(message = "매니저 계정 상태는 활성 또는 정지만 직접 설정할 수 있습니다.")
	public boolean isMutableStatus() {
		return status == null
				|| status == AccountStatus.ACTIVE
				|| status == AccountStatus.INACTIVE;
	}
}
