package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.Role;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ManagerSummaryResponse(
		UUID memberId,
		String name,
		String email,
		Role role,
		AccountStatus status,
		UUID organizationId,
		Instant lastLoginAt,
		List<ManagerAssignmentResponse> assignments
) {
	public ManagerSummaryResponse {
		assignments = List.copyOf(assignments);
	}
}
