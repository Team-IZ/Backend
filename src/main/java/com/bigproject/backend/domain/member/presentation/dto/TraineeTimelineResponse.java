package com.bigproject.backend.domain.member.presentation.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TraineeTimelineResponse(
		UUID cohortId, UUID traineeId, List<TimelineEntry> content, String nextCursor, boolean hasNext) {
	public enum Type { ASSESSMENT, REVIEW, REPORT, INTERVIEW }

	public record TimelineEntry(
			UUID timelineItemId, String type, String sourceEntityType, UUID sourceEntityId,
			UUID projectId, UUID assessmentRoundId, Integer analysisSequenceNo,
			String groupKey, OffsetDateTime groupSortAt, UUID teamId, String teamName,
			OffsetDateTime occurredAt, String sourceStatus, String title, String summary,
			String detailSummary, String problemResults, String reviewResultItems,
			String reviewChangeStatus, Integer reviewTargetCount, String interviewRecordStatus,
			String identifiedCauseSummary, String guidanceSummary, String nextActionSummary,
			boolean expandable, String detailActionCode, String aggregationStatus,
			boolean stale, OffsetDateTime asOfAt) {
	}
}
