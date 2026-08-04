package com.bigproject.backend.domain.manager.presentation.dto;

import com.bigproject.backend.domain.manager.domain.TraineeRoundRowResultStatus;
import com.bigproject.backend.domain.member.domain.AccountStatus;

import java.util.List;
import java.util.UUID;

public record TraineeRosterItemResponse(
		UUID cohortMemberId,
		UUID userId,
		String name,
		String email,
		AccountStatus accountStatus,
		UUID classId,
		String className,
		TraineeRoundRowResultStatus rowResultStatus,
		List<ConceptResultItemResponse> concepts,
		int expectedConceptCount,
		int scoredConceptCount,
		Integer lowStageConceptCount,
		boolean excellentThisRound,
		int excellentOccurrenceCount
) {
	public TraineeRosterItemResponse {
		concepts = List.copyOf(concepts);
	}
}
