package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.AssessmentActivityEventRepository;
import com.bigproject.backend.domain.assessment.domain.AssessmentActivityEventRepository.EventRow;
import com.bigproject.backend.domain.assessment.domain.AssessmentActivityEventRepository.ProblemRow;
import com.bigproject.backend.domain.assessment.domain.AssessmentValidityErrorCode;
import com.bigproject.backend.domain.assessment.presentation.dto.ProblemActivityEventsResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.ProblemActivityEventsResponse.ActivityEventSummary;
import com.bigproject.backend.domain.assessment.presentation.dto.ProblemActivityEventsResponse.ActivityEventSummary.Occurrence;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 매니저용 이벤트 로그 조회. attemptId로 들어와 세션을 확정한 뒤, 그 세션의 문제(질문)별로
 * {@code problem_stage_activity_log}를 집계해 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class AssessmentActivityEventService {

	private final ManagerViewScopeGuard scopeGuard;
	private final AssessmentActivityEventRepository repository;

	public List<ProblemActivityEventsResponse> find(String email, UUID attemptId) {
		var actor = scopeGuard.requireManager(email);
		UUID sessionId = repository.findScopedSessionId(attemptId, actor.userId())
				.orElseThrow(() -> new ApiException(AssessmentValidityErrorCode.ASSESSMENT_ATTEMPT_NOT_FOUND));

		List<ProblemRow> problems = repository.findProblems(sessionId);
		Map<UUID, List<EventRow>> eventsByProblem = repository.findEvents(sessionId).stream()
				.collect(Collectors.groupingBy(EventRow::problemId, LinkedHashMap::new, Collectors.toList()));

		return problems.stream()
				.map(problem -> new ProblemActivityEventsResponse(
						problem.problemId(), problem.problemNo(),
						summarize(eventsByProblem.getOrDefault(problem.problemId(), List.of()))))
				.toList();
	}

	/** 문제 하나의 이벤트 원본 행을 타입별로 묶어 횟수·합계·원본 목록을 만든다. */
	private List<ActivityEventSummary> summarize(List<EventRow> rows) {
		Map<String, List<EventRow>> byType = rows.stream()
				.collect(Collectors.groupingBy(EventRow::eventType, LinkedHashMap::new, Collectors.toList()));

		return byType.entrySet().stream()
				.map(entry -> new ActivityEventSummary(
						entry.getKey(),
						entry.getValue().size(),
						entry.getValue().stream().mapToInt(EventRow::durationMs).sum(),
						entry.getValue().stream()
								.map(row -> new Occurrence(row.occurredAt(), row.durationMs()))
								.toList()))
				.toList();
	}
}
