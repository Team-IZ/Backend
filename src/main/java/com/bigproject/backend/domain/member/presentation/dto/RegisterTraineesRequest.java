package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegisterTraineesRequest(
		@Schema(description = "직접 입력으로 등록·초대할 교육생 목록. 각 행은 독립적으로 처리됩니다.")
		@NotEmpty List<@NotNull @Valid Trainee> trainees
) {
	public record Trainee(
			@Schema(description = "교육생 명단에 저장할 필수 이름", example = "홍길동")
			@NotBlank @Size(max = 200) String name,
			@Schema(description = "교육생 로그인·초대 이메일. 형식·요청 내부 중복·기존 기관 계정을 행별 검증합니다.", example = "trainee@example.com")
			String email
	) {
	}
}
