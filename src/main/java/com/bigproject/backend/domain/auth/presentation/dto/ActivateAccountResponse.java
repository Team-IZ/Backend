package com.bigproject.backend.domain.auth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.bigproject.backend.domain.member.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "초대 수락과 계정 활성화 결과. `role`은 이 흐름에서 OPERATOR·MANAGER·TRAINEE 중 하나다.")
public record ActivateAccountResponse(
		@Schema(description = "활성화된 사용자 ID", type = "string", example = "UUID")
		@JsonProperty("user_id") UUID userId,
		@Schema(description = "초대 링크로 소유 확인된 로그인 이메일", example = "user@example.com") String email,
		@Schema(description = "활성화된 계정의 표시 이름", example = "홍길동") String name,
		Role role,
		@Schema(description = "계정·동의·초대 수락·토큰 사용 처리가 모두 완료되었는지 여부", example = "true") boolean activated
) {
}
