package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.Role;

import java.time.Instant;

public record InviteManagerResponse(
		Long memberId,
		String email,
		Role role,
		AccountStatus status,
		Instant invitedAt
) {
}
