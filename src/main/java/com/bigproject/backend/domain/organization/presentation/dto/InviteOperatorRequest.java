package com.bigproject.backend.domain.organization.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 오퍼레이터 초대 요청. 목업 SA-02 ② `오퍼레이터 초대` 모달은 <b>입력이 이메일 하나뿐</b>이다.
 *
 * <p>목업 주석 그대로: "권한을 고르는 칸이 없다 — 이 화면에서 보내는 초대는 오퍼레이터 하나뿐이라
 * 초대하는 화면이 곧 역할이다. 기수 배정도 없다 — 오퍼레이터는 기관 전체를 본다."
 * 그래서 역할·기수·반 필드를 두지 않고 서버가 오퍼레이터로 고정한다.
 */
@Schema(description = "오퍼레이터 초대 요청 (이메일만)")
public record InviteOperatorRequest(

		@Schema(description = "초대할 오퍼레이터 이메일", example = "choi@green.com")
		@NotBlank @Email @Size(max = 320)
		String email
) {
}
