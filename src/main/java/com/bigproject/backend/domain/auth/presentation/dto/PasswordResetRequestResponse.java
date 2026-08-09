package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PasswordResetRequestResponse(
		@Schema(example = "입력하신 주소가 계정에 등록돼 있으면 안내 메일이 도착합니다.")
		String message
) {
}
