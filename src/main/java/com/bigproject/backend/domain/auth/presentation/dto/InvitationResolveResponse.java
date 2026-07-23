package com.bigproject.backend.domain.auth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record InvitationResolveResponse(
		@JsonProperty("user_id") UUID userId,
		String email
) {
}
