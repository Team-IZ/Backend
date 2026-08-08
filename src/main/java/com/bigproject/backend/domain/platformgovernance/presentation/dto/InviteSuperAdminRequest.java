package com.bigproject.backend.domain.platformgovernance.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 슈퍼어드민 초대 요청. 목업 SA-03 ② `+ 계정 초대` 모달은 <b>입력이 이메일 하나뿐</b>이다.
 *
 * <p>역할을 고르는 칸이 없다 — 이 화면에서 보내는 초대는 슈퍼어드민 하나뿐이라 초대하는 화면이 곧 역할이다.
 * 기관·기수·반도 없다 — 슈퍼어드민은 어느 기관에도 속하지 않는다.
 */
@Schema(description = "슈퍼어드민 초대 요청 (이메일만)")
public record InviteSuperAdminRequest(

		@Schema(description = "초대할 슈퍼어드민 이메일", example = "sera@iz-get.com")
		@NotBlank @Email @Size(max = 320)
		String email
) {
}
