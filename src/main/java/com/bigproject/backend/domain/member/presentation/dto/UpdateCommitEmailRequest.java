package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCommitEmailRequest(
		@Schema(
				description = """
						커밋에 사용하는 이메일. 같은 값을 여러 번 보내도 결과가 같은 멱등 요청입니다. \
						등록·변경 모두 검증 상태를 PENDING으로 되돌립니다.""",
				example = "gildong@example.com"
		)
		@NotBlank @Email @Size(max = 320) String commitEmail
) {
}
