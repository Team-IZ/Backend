package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberStatusRequest(
		@Schema(description = "변경할 회원 계정 상태", example = "INACTIVE")
		@NotNull AccountStatus status,
		@Schema(description = "상태 변경 사유", example = "퇴사 처리")
		String reason
) {
}
