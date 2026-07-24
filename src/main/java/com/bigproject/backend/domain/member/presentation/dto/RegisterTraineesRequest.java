package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegisterTraineesRequest(
		@Schema(description = "일괄 등록·초대할 교육생 목록")
		@NotEmpty List<@NotNull @Valid Trainee> trainees
) {
	public record Trainee(
			@Schema(description = "교육생 이름", example = "홍길동")
			@NotBlank @Size(max = 200) String name,
			@Schema(description = "교육생 로그인·초대 이메일", example = "trainee@example.com")
			@NotBlank @Email @Size(max = 320) String email
	) {
	}
}
