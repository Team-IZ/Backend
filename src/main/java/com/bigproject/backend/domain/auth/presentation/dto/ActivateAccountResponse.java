package com.bigproject.backend.domain.auth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.bigproject.backend.domain.member.domain.Role;

import java.util.UUID;

public record ActivateAccountResponse(
		@JsonProperty("user_id") UUID userId,
		String email,
		String name,
		Role role,
		boolean activated
) {
}
