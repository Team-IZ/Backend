package com.bigproject.backend.domain.auth.presentation.dto;

import com.bigproject.backend.domain.member.domain.Role;

public record ActivateAccountResponse(
		Long memberId,
		String email,
		String name,
		Role role,
		boolean activated
) {
}
