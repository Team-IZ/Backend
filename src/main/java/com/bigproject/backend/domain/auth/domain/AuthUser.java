package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.domain.member.domain.Role;

import java.time.Instant;
import java.util.UUID;

public record AuthUser(
		UUID userId,
		UUID organizationId,
		String email,
		String name,
		String passwordHash,
		String status,
		boolean emailVerified,
		Instant lockedUntil,
		Instant passwordChangedAt,
		Role role,
		String organizationStatus
) {
	public AuthUser(
			UUID userId,
			UUID organizationId,
			String email,
			String name,
			String passwordHash,
			String status,
			boolean emailVerified,
			Instant lockedUntil,
			Role role,
			String organizationStatus
	) {
		this(
				userId,
				organizationId,
				email,
				name,
				passwordHash,
				status,
				emailVerified,
				lockedUntil,
				null,
				role,
				organizationStatus
		);
	}
}
