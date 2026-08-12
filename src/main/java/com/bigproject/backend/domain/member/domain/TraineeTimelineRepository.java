package com.bigproject.backend.domain.member.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TraineeTimelineRepository {
	List<TimelineRow> findAll(UUID managerId, UUID cohortId, UUID traineeId, String type);

	record TimelineRow(
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
