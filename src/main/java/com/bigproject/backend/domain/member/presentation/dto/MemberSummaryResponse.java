package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.Role;

import java.time.Instant;
import java.util.List;

public record MemberSummaryResponse(
		Long memberId,
		String name,
		String email,
		Role role,
		AccountStatus status,
		Long organizationId,
		List<Long> cohortIds,
		List<Long> classroomIds,
		Instant lastLoginAt
) {
}
