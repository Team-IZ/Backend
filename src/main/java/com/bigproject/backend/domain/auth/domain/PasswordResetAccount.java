package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.domain.member.domain.Role;

import java.util.UUID;

public record PasswordResetAccount(
		UUID userId,
		UUID organizationId,
		String organizationName,
		String email,
		String name,
		String normalizedEmail,
		String passwordHash,
		String status,
		Role role,
		UUID invitationId,
		UUID cohortId,
		String cohortName
) {
}
