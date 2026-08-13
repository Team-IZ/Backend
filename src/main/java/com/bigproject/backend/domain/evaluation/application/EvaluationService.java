package com.bigproject.backend.domain.evaluation.application;

import com.bigproject.backend.domain.evaluation.domain.EvaluationErrorCode;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository.ConceptResultRow;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository.RoundScope;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository.StageRow;
import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository.TraineeRow;
import com.bigproject.backend.domain.evaluation.presentation.dto.ProjectEvaluationSummaryResponse;
import com.bigproject.backend.domain.evaluation.presentation.dto.TraineeEvaluationDetailResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewAccessErrorCode;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MG-08 결과 탭의 조회 서비스.
 *
 * <p><b>판정을 화면에 넘기지 않는 것</b>이 이 클래스의 일이다. 도달 단계, 다시 보기 대상 개념,
 * 불합격 인원, 집단 미달 경고가 모두 여기서 나온다. 화면이 개념 배열을 세어 유추하면 같은 규칙이
 * 두 곳에 생기고, 한쪽만 고치는 순간 면담 자료가 조용히 어긋난다.
 *
 * <p>공식 결과는 최초 응시(INITIAL)뿐이다. 재시험·다시 보기는 최초 결과를 덮지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EvaluationService {

	/** 2단 미달이면 불합격이다(정의서 §3 "개념 1개 이상에서 2단 미달"). */
	private static final int PASS_LEVEL = 2;

	/** 판정이 끝난 수행. 이 상태의 사람만 합격·불합격을 말할 수 있다. */
	private static final String SETTLED_STATUS = "AVAILABLE";

	private final EvaluationQueryRepository repository;
	private final ManagerViewScopeGuard scopeGuard;

	public ProjectEvaluationSummaryResponse findSummary(
			String email, UUID projectId, int roundNo, UUID classId) {
		Scope scope = resolveScope(email, projectId, roundNo, classId);

		List<TraineeRow> traineeRows = repository.findTrainees(
				scope.round().assessmentRoundId(), scope.organizationId(), scope.managerUserId(), classId);
		List<ConceptResultRow> conceptRows = repository.findConceptResults(
				scope.round().assessmentRoundId(), scope.organizationId(), scope.managerUserId(), classId, null);

		Map<UUID, List<ConceptResultRow>> conceptsByUser = conceptRows.stream()
				.collect(Collectors.groupingBy(ConceptResultRow::userId));

		List<ProjectEvaluationSummaryResponse.Trainee> trainees = traineeRows.stream()
				.map(row -> toTrainee(row, conceptsByUser.getOrDefault(row.userId(), List.of())))
				.toList();

		long attendedCount = trainees.stream().filter(t -> "AVAILABLE".equals(t.resultStatus())).count();

		return new ProjectEvaluationSummaryResponse(
				scope.round().projectId(),
				scope.round().projectName(),
				scope.round().assessmentRoundId(),
				scope.round().roundNo(),
				scope.round().roundName(),
				scope.round().reportPublished(),
				scope.round().publishedAt(),
				!conceptRows.isEmpty(),
				summarize(trainees, attendedCount),
				classWarnings(trainees, attendedCount),
				conceptAggregates(trainees),
				trainees
		);
	}

	public TraineeEvaluationDetailResponse findTraineeDetail(
			String email, UUID projectId, int roundNo, UUID userId) {
		Scope scope = resolveScope(email, projectId, roundNo, null);

		// 담당 반 전체 명단에서 찾는다 — 명단에 없으면 그 회차 대상이 아니거나 남의 반이고,
		// 둘을 구분해 주면 담당 밖에 누가 있는지 확인할 수 있으므로 하나의 404로 합친다.
		TraineeRow traineeRow = repository
				.findTrainees(scope.round().assessmentRoundId(), scope.organizationId(),
						scope.managerUserId(), null)
				.stream()
				.filter(row -> row.userId().equals(userId))
				.findFirst()
				.orElseThrow(() -> new ApiException(EvaluationErrorCode.EVALUATION_TRAINEE_NOT_FOUND));

		List<ConceptResultRow> conceptRows = repository.findConceptResults(
				scope.round().assessmentRoundId(), scope.organizationId(), scope.managerUserId(), null, userId);
		Map<UUID, List<StageRow>> stagesByProblem = repository
				.findStages(scope.round().assessmentRoundId(), userId).stream()
				.collect(Collectors.groupingBy(StageRow::problemId));

		String resultStatus = resultStatus(traineeRow);
		boolean settled = SETTLED_STATUS.equals(resultStatus);
		List<TraineeEvaluationDetailResponse.Concept> concepts = conceptRows.stream()
				.map(row -> new TraineeEvaluationDetailResponse.Concept(
						row.conceptId(),
						row.conceptName(),
						row.displayOrder(),
						row.generated(),
						row.reachLevel(),
						retryTarget(row, settled),
						steps(stagesByProblem.getOrDefault(row.problemId(), List.of()))))
				.toList();

		return new TraineeEvaluationDetailResponse(
				scope.round().projectId(),
				scope.round().assessmentRoundId(),
				scope.round().roundNo(),
				traineeRow.userId(),
				traineeRow.name(),
				traineeRow.classId(),
				traineeRow.className(),
				scope.round().reportPublished(),
				resultStatus,
				concepts
		);
	}

	/** 회차 확인 → 기수 담당 확인 → (지정했다면) 반 담당 확인. 셋 다 통과해야 조회가 나간다. */
	private Scope resolveScope(String email, UUID projectId, int roundNo, UUID classId) {
		RoundScope round = repository.findRound(projectId, roundNo)
				.orElseThrow(() -> new ApiException(EvaluationErrorCode.PROJECT_ROUND_NOT_FOUND));
		var actor = scopeGuard.requireCohort(email, round.cohortId());
		if (classId != null && !repository.isClassManagedBy(actor.userId(), classId, round.cohortId())) {
			throw new ApiException(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND);
		}
		return new Scope(round, round.organizationId(), actor.userId());
	}

	private ProjectEvaluationSummaryResponse.Trainee toTrainee(
			TraineeRow row, List<ConceptResultRow> conceptRows) {
		String resultStatus = resultStatus(row);
		boolean settled = SETTLED_STATUS.equals(resultStatus);
		List<ProjectEvaluationSummaryResponse.ConceptOutcome> concepts = conceptRows.stream()
				.map(concept -> new ProjectEvaluationSummaryResponse.ConceptOutcome(
						concept.conceptId(),
						concept.conceptName(),
						concept.displayOrder(),
						concept.generated(),
						concept.reachLevel(),
						retryTarget(concept, settled)))
				.toList();

		return new ProjectEvaluationSummaryResponse.Trainee(
				row.userId(),
				row.name(),
				row.classId(),
				row.className(),
				resultStatus,
				concepts.stream().filter(ProjectEvaluationSummaryResponse.ConceptOutcome::retryTarget).count(),
				concepts
		);
	}

	/**
	 * 다시 보기 대상은 <b>수행이 끝난 사람에게만</b> 붙는다.
	 *
	 * <p>도달 단계만 보면 아직 응시 중인 사람의 안 푼 문제가 전부 "0단 = 2단 미달"이라 대상이 된다.
	 * 실제로 그랬다 — 1번 문제를 푸는 중인 교육생이 2·3번 때문에 '막힘 2'로 뜨고 불합격 인원에도
	 * 잡혔다. 묻지도 않은 것을 못했다고 판정한 셈이라, 판정 자체를 종료된 수행으로 제한한다.
	 *
	 * <p>코드에 없던 개념도 대상이 아니다 — 못한 것이 아니라 묻지 못한 것이라 다시 보여줄 것이 없다.
	 */
	private boolean retryTarget(ConceptResultRow row, boolean settled) {
		return settled && row.generated() && row.reachLevel() < PASS_LEVEL;
	}

	/**
	 * 무효 확정을 가장 먼저 본다. 무효인 수행은 응시를 마쳤더라도 결과로 읽으면 안 된다.
	 *
	 * <p>{@code INCOMPLETE}(중단)를 {@code IN_PROGRESS}와 가른다. 응시 창이 닫히도록 끝내지 못한 사람은
	 * 더 손쓸 것이 없는 확정 상태인데, 하나로 접으면 마감이 지난 뒤에도 화면에 "아직 응시 중"으로 남는다.
	 * 제출 현황 탭이 {@code MISSED}와 {@code OPEN}을 가르는 것과 같은 원칙이다.
	 */
	private String resultStatus(TraineeRow row) {
		if ("CONFIRMED_INVALID".equals(row.validityReviewStatus())) {
			return "INVALID";
		}
		if ("NOT_ATTENDED".equals(row.terminalReasonCode())) {
			return "NOT_ATTENDED";
		}
		if ("SESSION_INCOMPLETE".equals(row.terminalReasonCode())) {
			return "INCOMPLETE";
		}
		return "COMPLETED".equals(row.completionStatus()) ? SETTLED_STATUS : "IN_PROGRESS";
	}

	private ProjectEvaluationSummaryResponse.Summary summarize(
			List<ProjectEvaluationSummaryResponse.Trainee> trainees, long attendedCount) {
		return new ProjectEvaluationSummaryResponse.Summary(
				trainees.size(),
				attendedCount,
				trainees.stream().filter(t -> t.stuckConceptCount() > 0).count(),
				trainees.stream().filter(t -> "NOT_ATTENDED".equals(t.resultStatus())).count(),
				trainees.stream().filter(t -> "INVALID".equals(t.resultStatus())).count()
		);
	}

	/**
	 * 유효 응시자의 <b>절반을 넘는</b> 인원이 막힌 개념만 경고한다. 절반 '이상'이 아니라 초과인 이유는
	 * 히트맵의 집단 미달 판정과 같은 산식을 쓰기 위해서다 — 두 화면이 다른 기준으로 같은 개념을
	 * 경고하거나 안 하면 매니저가 어느 쪽을 믿을지 알 수 없다.
	 */
	private List<ProjectEvaluationSummaryResponse.ClassWarning> classWarnings(
			List<ProjectEvaluationSummaryResponse.Trainee> trainees, long attendedCount) {
		if (attendedCount == 0) {
			return List.of();
		}
		Map<UUID, long[]> stuckByConcept = new LinkedHashMap<>();
		Map<UUID, String> nameByConcept = new LinkedHashMap<>();
		for (var trainee : trainees) {
			for (var concept : trainee.concepts()) {
				nameByConcept.putIfAbsent(concept.conceptId(), concept.concept());
				if (concept.retryTarget()) {
					stuckByConcept.computeIfAbsent(concept.conceptId(), key -> new long[1])[0]++;
				}
			}
		}
		List<ProjectEvaluationSummaryResponse.ClassWarning> warnings = new ArrayList<>();
		stuckByConcept.forEach((conceptId, count) -> {
			if (count[0] * 2 > attendedCount) {
				warnings.add(new ProjectEvaluationSummaryResponse.ClassWarning(
						conceptId, nameByConcept.get(conceptId), count[0], attendedCount));
			}
		});
		return warnings;
	}

	/**
	 * 이미 판정이 끝난 {@code trainees}에서 접는다 — 원본 행에서 다시 세면 '막힘' 기준이 두 곳에 생기고,
	 * 진행 중인 사람을 빼는 규칙이 한쪽에만 반영되는 사고가 난다.
	 *
	 * <p>'코드에 없던 사람'은 응시 여부와 무관하다 — 그 개념이 코드에 없었다는 것은 제출한 코드의 성질이지
	 * 그 사람이 어디까지 했는가가 아니다.
	 */
	private List<ProjectEvaluationSummaryResponse.ConceptAggregate> conceptAggregates(
			List<ProjectEvaluationSummaryResponse.Trainee> trainees) {
		Map<UUID, Aggregate> byConcept = new LinkedHashMap<>();
		for (var trainee : trainees) {
			var person = new ProjectEvaluationSummaryResponse.Person(trainee.userId(), trainee.name());
			for (var concept : trainee.concepts()) {
				Aggregate aggregate = byConcept.computeIfAbsent(concept.conceptId(),
						key -> new Aggregate(concept.concept(), concept.displayOrder()));
				if (!concept.inCode()) {
					aggregate.notInCode().add(person);
				} else if (concept.retryTarget()) {
					aggregate.stuck().add(person);
				}
			}
		}

		return byConcept.entrySet().stream()
				.map(entry -> new ProjectEvaluationSummaryResponse.ConceptAggregate(
						entry.getKey(),
						entry.getValue().concept(),
						entry.getValue().displayOrder(),
						entry.getValue().stuck().size(),
						entry.getValue().notInCode().size(),
						sortedByName(entry.getValue().stuck()),
						sortedByName(entry.getValue().notInCode())))
				.sorted(Comparator.comparingInt(ProjectEvaluationSummaryResponse.ConceptAggregate::displayOrder))
				.toList();
	}

	private List<ProjectEvaluationSummaryResponse.Person> sortedByName(
			List<ProjectEvaluationSummaryResponse.Person> people) {
		return people.stream()
				.sorted(Comparator.comparing(ProjectEvaluationSummaryResponse.Person::name,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
	}

	private List<TraineeEvaluationDetailResponse.Step> steps(List<StageRow> stageRows) {
		return stageRows.stream()
				.map(row -> new TraineeEvaluationDetailResponse.Step(
						row.axisCode(),
						stepNo(row.axisCode()),
						row.passed(),
						row.helpCount(),
						row.score(),
						row.note()))
				.sorted(Comparator.comparingInt(TraineeEvaluationDetailResponse.Step::stepNo))
				.toList();
	}

	/** L1~L4를 사다리 순서 1~4로. 알 수 없는 값은 뒤로 보낸다. */
	private int stepNo(String axisCode) {
		return switch (axisCode == null ? "" : axisCode) {
			case "L1" -> 1;
			case "L2" -> 2;
			case "L3" -> 3;
			case "L4" -> 4;
			default -> Integer.MAX_VALUE;
		};
	}

	private record Scope(RoundScope round, UUID organizationId, UUID managerUserId) {
	}

	private record Aggregate(
			String concept,
			int displayOrder,
			List<ProjectEvaluationSummaryResponse.Person> stuck,
			List<ProjectEvaluationSummaryResponse.Person> notInCode
	) {
		private Aggregate(String concept, int displayOrder) {
			this(concept, displayOrder, new ArrayList<>(), new ArrayList<>());
		}
	}
}
