package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record InviteManagerResponse(
		@Schema(type = "string", example = "UUID") UUID memberId,
		String email,
		Role role,
		AccountStatus status,
		Instant invitedAt
) {
}
