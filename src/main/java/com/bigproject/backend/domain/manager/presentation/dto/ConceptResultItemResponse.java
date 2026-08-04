package com.bigproject.backend.domain.manager.presentation.dto;

import java.util.UUID;

public record ConceptResultItemResponse(
		UUID conceptId,
		int sequenceNo,
		String conceptName,
		String generationStatus,
		Integer highestReachedLevel
) {
}
