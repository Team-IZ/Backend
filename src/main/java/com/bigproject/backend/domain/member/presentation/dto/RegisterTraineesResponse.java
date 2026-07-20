package com.bigproject.backend.domain.member.presentation.dto;

import java.util.List;

public record RegisterTraineesResponse(
		int requestedCount,
		int registeredCount,
		int invitationSentCount,
		List<Failure> failures
) {
	public record Failure(int row, String email, String reason) {
	}
}
