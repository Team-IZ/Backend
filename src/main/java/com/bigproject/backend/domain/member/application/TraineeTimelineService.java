package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.TraineeTimelineRepository;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.presentation.dto.TraineeTimelineResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraineeTimelineService {
	private final TraineeTimelineRepository repository;
	private final ManagerViewScopeGuard scopeGuard;

	public TraineeTimelineResponse findTimeline(
			String email, UUID cohortId, UUID traineeId, TraineeTimelineResponse.Type type,
			String cursor, int size) {
		var actor = scopeGuard.requireCohort(email, cohortId);
		var rows = repository.findAll(actor.userId(), cohortId, traineeId, type == null ? null : type.name());
		UUID after = decode(cursor);
		if (after != null) {
			int index = -1;
			for (int i = 0; i < rows.size(); i++) {
				if (rows.get(i).timelineItemId().equals(after)) {
					index = i;
					break;
				}
			}
			rows = index < 0 ? java.util.List.of() : rows.subList(index + 1, rows.size());
		}
		boolean hasNext = rows.size() > size;
		var page = rows.stream().limit(size).toList();
		var items = page.stream().map(row -> new TraineeTimelineResponse.TimelineEntry(
				row.timelineItemId(), row.type(), row.sourceEntityType(), row.sourceEntityId(),
				row.projectId(), row.assessmentRoundId(), row.analysisSequenceNo(), row.groupKey(),
				row.groupSortAt(), row.teamId(), row.teamName(), row.occurredAt(), row.sourceStatus(),
				row.title(), row.summary(), row.detailSummary(), row.problemResults(), row.reviewResultItems(),
				row.reviewChangeStatus(), row.reviewTargetCount(), row.interviewRecordStatus(),
				row.identifiedCauseSummary(), row.guidanceSummary(), row.nextActionSummary(),
				row.expandable(), row.detailActionCode(), row.aggregationStatus(), row.stale(), row.asOfAt()))
				.toList();
		String next = hasNext && !page.isEmpty() ? encode(page.get(page.size() - 1).timelineItemId()) : null;
		return new TraineeTimelineResponse(cohortId, traineeId, items, next, hasNext);
	}

	private UUID decode(String cursor) {
		if (cursor == null || cursor.isBlank()) return null;
		try {
			return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
		} catch (IllegalArgumentException exception) {
			throw new ApiException(MemberErrorCode.TIMELINE_CURSOR_INVALID);
		}
	}

	private String encode(UUID id) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(id.toString().getBytes(StandardCharsets.UTF_8));
	}
}
