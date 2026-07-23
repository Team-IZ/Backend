package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.domain.member.domain.Role;

import java.util.UUID;

public record AccountActivationTarget(
		UUID tokenId,
		UUID userId,
		String email,
		String name,
		Role role,
		int rowVersion
) {
}
