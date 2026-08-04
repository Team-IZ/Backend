package com.bigproject.backend.domain.manager.presentation.dto;

import java.util.UUID;

public record TraineeRosterContextResponse(
		UUID cohortId,
		UUID selectedProjectId,
		UUID selectedAssessmentRoundId,
		int roundNo,
		String roundName,
		int analysisSequenceNo,
		int accessibleClassCount,
		UUID selectedClassId,
		long totalTraineeCount,
		long activeTraineeCount,
		long invitationPendingTraineeCount,
		long inactiveTraineeCount,
		long filteredTotalCount
) {
}
