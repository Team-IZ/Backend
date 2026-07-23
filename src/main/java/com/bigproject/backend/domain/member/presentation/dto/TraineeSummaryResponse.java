package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record TraineeSummaryResponse(
		UUID memberId,
		String name,
		String email,
		AccountStatus status,
		String membershipStatus,
		Instant leftAt,
		UUID classroomId,
		String classroomName
) {
}
