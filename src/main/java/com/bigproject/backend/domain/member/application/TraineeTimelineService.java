package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.domain.TraineeTimelineRepository;
import com.bigproject.backend.domain.member.presentation.dto.TraineeTimelineResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraineeTimelineService {
	private static final String ASSESSMENT = "ASSESSMENT";
	private static final String REPORT = "REPORT";
	private static final String REVIEW = "REVIEW";
	private static final String REVIEW_CLOSED = "REVIEW_CLOSED";
	private static final String INTERVIEW = "INTERVIEW";

	private final TraineeTimelineRepository repository;
	private final ManagerViewScopeGuard scopeGuard;
	private final ObjectMapper objectMapper;

	public TraineeTimelineResponse findTimeline(
			String email, UUID cohortId, UUID traineeId, TraineeTimelineResponse.Type type,
			String cursor, int size) {
		var actor = scopeGuard.requireCohort(email, cohortId);
		String eventType = type == null ? null : type.name();

		int totalElements = repository.countEvents(actor.userId(), cohortId, traineeId, eventType);

		// 회차를 한 개 더 읽어 다음 페이지가 있는지 본다. 페이지 단위가 회차라 이벤트를 세지 않는다.
		List<Integer> sequenceNos = repository.findRoundSequenceNos(
				actor.userId(), cohortId, traineeId, eventType, decode(cursor), size + 1);
		boolean hasNext = sequenceNos.size() > size;
		List<Integer> page = hasNext ? sequenceNos.subList(0, size) : sequenceNos;

		List<TraineeTimelineRepository.EventRow> rows =
				repository.findEvents(actor.userId(), cohortId, traineeId, eventType, page);

		String next = hasNext && !page.isEmpty() ? encode(page.get(page.size() - 1)) : null;
		return new TraineeTimelineResponse(
				cohortId, traineeId, totalElements, toRounds(rows), next, hasNext);
	}

	/**
	 * 회차별로 묶는다. 뷰가 회차 문맥을 이벤트마다 실어 주므로 첫 행의 값이 그 회차를 대표한다 --
	 * 같은 회차의 행은 회차 칸이 모두 같다(round_context를 조인해 만든다).
	 */
	private List<TraineeTimelineResponse.Round> toRounds(
			List<TraineeTimelineRepository.EventRow> rows) {
		Map<UUID, List<TraineeTimelineRepository.EventRow>> grouped = new LinkedHashMap<>();
		for (TraineeTimelineRepository.EventRow row : rows) {
			grouped.computeIfAbsent(row.assessmentRoundId(), key -> new ArrayList<>()).add(row);
		}

		List<TraineeTimelineResponse.Round> rounds = new ArrayList<>();
		for (List<TraineeTimelineRepository.EventRow> group : grouped.values()) {
			TraineeTimelineRepository.EventRow head = group.get(0);
			rounds.add(new TraineeTimelineResponse.Round(
					head.assessmentRoundId(), head.projectId(), head.analysisSequenceNo(),
					head.roundName(), head.projectName(), head.teamId(), head.teamName(),
					head.roundStartAt(), head.roundEndAt(),
					group.stream().map(this::toEvent).toList()));
		}
		return List.copyOf(rounds);
	}

	/**
	 * 유형별 payload를 푼다. 뷰가 JSONB 한 칸에 담는 이유는 유형마다 있는 필드가 달라
	 * 컬럼으로 펴면 대부분이 NULL인 넓은 행이 되기 때문이다. 화면이 매번 파싱하지 않도록
	 * 여기서 타입으로 바꿔 준다.
	 */
	private TraineeTimelineResponse.Event toEvent(TraineeTimelineRepository.EventRow row) {
		JsonNode payload = parse(row.payload());
		String type = row.eventType();
		return new TraineeTimelineResponse.Event(
				row.eventId(), type, row.occurredAt(),
				row.sourceEntityType(), row.sourceEntityId(), row.sourceStatus(),
				row.sessionId(), row.expandable(), row.detailActionCode(), row.aggregationStatus(),
				ASSESSMENT.equals(type) ? problems(payload.path("problems")) : List.of(),
				REPORT.equals(type) ? integer(payload, "reviewTargetCount") : null,
				REPORT.equals(type) ? text(payload, "summary") : null,
				REVIEW.equals(type) || REVIEW_CLOSED.equals(type)
						? time(payload, "dueAt") : null,
				REVIEW.equals(type) ? reviewChanges(payload.path("changes")) : List.of(),
				REVIEW_CLOSED.equals(type) ? missedConcepts(payload.path("missed")) : List.of(),
				INTERVIEW.equals(type) ? interview(payload) : null);
	}

	private List<TraineeTimelineResponse.AssessmentProblem> problems(JsonNode items) {
		List<TraineeTimelineResponse.AssessmentProblem> result = new ArrayList<>();
		for (JsonNode item : items) {
			result.add(new TraineeTimelineResponse.AssessmentProblem(
					item.path("problemNo").asInt(),
					uuid(item, "problemId"),
					uuid(item, "conceptId"),
					text(item, "conceptName"),
					text(item, "generationStatus"),
					integer(item, "reachLevel"),
					integer(item, "hintUsedCount")));
		}
		return List.copyOf(result);
	}

	private List<TraineeTimelineResponse.ReviewChange> reviewChanges(JsonNode items) {
		List<TraineeTimelineResponse.ReviewChange> result = new ArrayList<>();
		for (JsonNode item : items) {
			result.add(new TraineeTimelineResponse.ReviewChange(
					item.path("problemNo").asInt(),
					uuid(item, "problemId"),
					uuid(item, "conceptId"),
					text(item, "conceptName"),
					integer(item, "fromReachLevel"),
					integer(item, "toReachLevel"),
					item.path("improved").asBoolean(false)));
		}
		return List.copyOf(result);
	}

	private List<TraineeTimelineResponse.MissedConcept> missedConcepts(JsonNode items) {
		List<TraineeTimelineResponse.MissedConcept> result = new ArrayList<>();
		for (JsonNode item : items) {
			result.add(new TraineeTimelineResponse.MissedConcept(
					item.path("problemNo").asInt(),
					uuid(item, "problemId"),
					uuid(item, "conceptId"),
					text(item, "conceptName")));
		}
		return List.copyOf(result);
	}

	private TraineeTimelineResponse.Interview interview(JsonNode payload) {
		List<TraineeTimelineResponse.ManagerAction> actions = new ArrayList<>();
		for (JsonNode item : payload.path("managerActions")) {
			actions.add(new TraineeTimelineResponse.ManagerAction(
					integer(item, "displayOrder"),
					text(item, "question"),
					text(item, "rationale")));
		}
		return new TraineeTimelineResponse.Interview(
				text(payload, "recordStatus"),
				text(payload, "identifiedCause"),
				text(payload, "managerNote"),
				text(payload, "nextAction"),
				time(payload, "nextActionConfirmedAt"),
				List.copyOf(actions));
	}

	private JsonNode parse(String raw) {
		return raw == null || raw.isBlank()
				? objectMapper.createObjectNode()
				: objectMapper.readTree(raw);
	}

	private UUID uuid(JsonNode node, String field) {
		String value = text(node, field);
		return value == null ? null : UUID.fromString(value);
	}

	private String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isNull() || value.isMissingNode() ? null : value.asString();
	}

	private Integer integer(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isNumber() ? value.asInt() : null;
	}

	private OffsetDateTime time(JsonNode node, String field) {
		String value = text(node, field);
		return value == null ? null : OffsetDateTime.parse(value);
	}

	/** 커서는 회차 차수 하나다. 회차 정렬 키가 차수라 복합 키가 필요 없다. */
	private Integer decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			return Integer.valueOf(
					new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
		} catch (IllegalArgumentException exception) {
			// Base64 디코딩 실패와 정수 파싱 실패(NumberFormatException)가 모두 여기로 온다.
			throw new ApiException(MemberErrorCode.TIMELINE_CURSOR_INVALID);
		}
	}

	private String encode(int sequenceNo) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(String.valueOf(sequenceNo).getBytes(StandardCharsets.UTF_8));
	}
}
