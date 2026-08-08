package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record InvitationResendResponse(
		@Schema(example = "입력하신 주소로 초대를 보낸 기록이 있으면 초대 메일이 다시 도착합니다.")
		String message
) {
}
