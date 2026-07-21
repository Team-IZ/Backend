package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberStatusRequest(
		@NotNull AccountStatus status,
		String reason
) {
}
