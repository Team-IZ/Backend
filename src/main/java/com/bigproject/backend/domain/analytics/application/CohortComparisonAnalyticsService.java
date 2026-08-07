package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.ChangeDirection;
import com.bigproject.backend.domain.analytics.domain.CohortComparisonPolicy;
import com.bigproject.backend.domain.analytics.domain.CohortComparisonQueryRepository;
import com.bigproject.backend.domain.analytics.domain.ComparisonEmptyState;
import com.bigproject.backend.domain.analytics.domain.ComparisonSort;
import com.bigproject.backend.domain.analytics.domain.ConceptPresence;
import com.bigproject.backend.domain.analytics.domain.NotComparableReason;
import com.bigproject.backend.domain.analytics.presentation.dto.CohortComparisonResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CohortComparisonAnalyticsService {
	private static final String MERGED = "MERGED";

	private final AnalyticsActorGuard analyticsActorGuard;
	private final CohortComparisonQueryRepository cohortComparisonQueryRepository;

	public CohortComparisonResponse findCohortComparison(
			UUID cohortId,
			UUID baselineCohortId,
			ComparisonSort sort,
			boolean sameCurriculumOnly,
			String actorEmail
	) {
		AuthUser actor = analyticsActorGuard.operatorOrManager(actorEmail, "매니저만 기수 간 비교를 조회할 수 있습니다.");
		CohortComparisonQueryRepository.CohortRow target = cohortComparisonQueryRepository.findCohort(cohortId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기수를 찾을 수 없습니다."));
		analyticsActorGuard.requireSameOrganization(target.organizationId(), actor);

		UUID organizationId = target.organizationId();
		ComparisonSort appliedSort = sort == null ? ComparisonSort.WORSENED : sort;
		List<CohortComparisonQueryRepository.BaselineCandidateRow> candidates =
				cohortComparisonQueryRepository.findBaselineCandidates(organizationId, cohortId);
		CohortComparisonResponse.CohortRef targetRef =
				new CohortComparisonResponse.CohortRef(target.cohortId(), target.cohortName());

		// 비교 대상을 아직 고르지 않았으면 드롭다운 후보만 채운다.
		// 후보 자체가 없으면 이 기관의 첫 기수라 비교 자체가 성립하지 않는다.
		if (baselineCohortId == null) {
			return emptyResponse(
					targetRef,
					null,
					candidates,
					candidates.isEmpty() ? ComparisonEmptyState.NO_COMPARABLE_COHORT : null,
					appliedSort
			);
		}

		CohortComparisonQueryRepository.BaselineCandidateRow baseline = candidates.stream()
				.filter(candidate -> candidate.cohortId().equals(baselineCohortId))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.BAD_REQUEST, "같은 기관의 다른 기수만 비교 대상으로 지정할 수 있습니다."));
		CohortComparisonResponse.CohortRef baselineRef =
				new CohortComparisonResponse.CohortRef(baseline.cohortId(), baseline.cohortName());

		// 두 기수 모두 발행된 수업 진단 리포트가 있어야 읽을 스냅샷이 생긴다.
		if (!baseline.comparable() || !cohortComparisonQueryRepository.hasPublishedDiagnosis(cohortId, organizationId)) {
			return emptyResponse(
					targetRef, baselineRef, candidates, ComparisonEmptyState.REPORT_NOT_PUBLISHED, appliedSort, sameCurriculumOnly);
		}

		List<UUID> cohortIds = List.of(cohortId, baselineCohortId);
		List<CohortComparisonQueryRepository.ConceptLevelRow> levels =
				cohortComparisonQueryRepository.aggregateConceptLevels(organizationId, cohortIds);
		Map<UUID, CohortComparisonQueryRepository.ConceptLevelRow> targetLevels = levelsOf(levels, cohortId);
		Map<UUID, CohortComparisonQueryRepository.ConceptLevelRow> baselineLevels = levelsOf(levels, baselineCohortId);
		List<CohortComparisonQueryRepository.ConceptRoundRow> rounds =
				cohortComparisonQueryRepository.findConceptRounds(organizationId, cohortIds);
		Map<UUID, CohortComparisonQueryRepository.ConceptRoundRow> targetRounds = roundsOf(rounds, cohortId);
		Map<UUID, CohortComparisonQueryRepository.ConceptRoundRow> baselineRounds = roundsOf(rounds, baselineCohortId);

		// 두 기수에 공통으로 존재하는 개념이 하나도 없으면 격자의 모든 칸이 '비교 대상 아님'이 된다.
		boolean hasSharedConcept = targetLevels.keySet().stream().anyMatch(baselineLevels::containsKey);
		if (!hasSharedConcept) {
			return emptyResponse(
					targetRef, baselineRef, candidates, ComparisonEmptyState.NO_SHARED_CONCEPT, appliedSort, sameCurriculumOnly);
		}

		Set<UUID> everyConcept = new LinkedHashSet<>(targetLevels.keySet());
		everyConcept.addAll(baselineLevels.keySet());

		List<CohortComparisonResponse.ConceptComparison> concepts = everyConcept.stream()
				.map(teachesId -> toConcept(
						teachesId,
						targetLevels.get(teachesId),
						baselineLevels.get(teachesId),
						targetRounds.get(teachesId),
						baselineRounds.get(teachesId)
				))
				.filter(concept -> !sameCurriculumOnly || sameCurriculum(concept))
				.toList();

		return new CohortComparisonResponse(
				targetRef,
				baselineRef,
				toOptions(candidates),
				null,
				levelScale(),
				changeThreshold(),
				appliedSort,
				sameCurriculumOnly,
				sorted(concepts, appliedSort)
		);
	}

	/**
	 * 교안이 바뀌면 평균 차이가 교육생 변화인지 교안 변화인지 갈라 볼 수 없다.
	 * 두 기수가 같은 교안 버전을 쓴 개념만 남겨 그 혼동을 없앤다.
	 *
	 * 한쪽 기수에 없던 개념은 애초에 비교가 성립하지 않으므로 함께 걸러진다.
	 */
	private boolean sameCurriculum(CohortComparisonResponse.ConceptComparison concept) {
		CohortComparisonResponse.CurriculumVersionChange version = concept.curriculumVersion();
		return version != null
				&& version.baselineVersionNo() != null
				&& version.targetVersionNo() != null
				&& !version.versionChanged();
	}

	private CohortComparisonResponse.ConceptComparison toConcept(
			UUID teachesId,
			CohortComparisonQueryRepository.ConceptLevelRow targetRow,
			CohortComparisonQueryRepository.ConceptLevelRow baselineRow,
			CohortComparisonQueryRepository.ConceptRoundRow targetRound,
			CohortComparisonQueryRepository.ConceptRoundRow baselineRound
	) {
		// 출처 표기와 개념명은 이번 기수 기준이며, 이번 기수에 없는 개념만 지난 기수 값으로 채운다.
		CohortComparisonQueryRepository.ConceptLevelRow display = targetRow != null ? targetRow : baselineRow;
		CohortComparisonQueryRepository.ConceptRoundRow round = targetRound != null ? targetRound : baselineRound;

		CohortComparisonResponse.CohortConceptValue target = toValue(targetRow);
		CohortComparisonResponse.CohortConceptValue baseline = toValue(baselineRow);

		return new CohortComparisonResponse.ConceptComparison(
				teachesId,
				display.conceptName(),
				new CohortComparisonResponse.ConceptSource(
						display.curriculumTitle(),
						display.sectionSequenceNo(),
						display.sectionTitle(),
						display.pageStart(),
						display.pageEnd(),
						round == null ? null : round.roundNo(),
						round == null ? null : round.roundName()
				),
				target,
				baseline,
				toChange(target, baseline, targetRow, baselineRow),
				new CohortComparisonResponse.CurriculumVersionChange(
						baselineRow == null ? null : baselineRow.curriculumVersionNo(),
						targetRow == null ? null : targetRow.curriculumVersionNo(),
						versionChanged(targetRow, baselineRow)
				)
		);
	}

	private CohortComparisonResponse.CohortConceptValue toValue(
			CohortComparisonQueryRepository.ConceptLevelRow row
	) {
		if (row == null) {
			return new CohortComparisonResponse.CohortConceptValue(
					null, null, 0, 0, ConceptPresence.ABSENT_IN_COHORT, null);
		}
		BigDecimal average = averageReachedLevel(row);
		return new CohortComparisonResponse.CohortConceptValue(
				average,
				CohortComparisonPolicy.bandOf(average),
				row.participantCount(),
				row.missingCount(),
				MERGED.equals(row.teachesStatus()) ? ConceptPresence.MERGED : ConceptPresence.PRESENT,
				row.aggregationStatus()
		);
	}

	/**
	 * 분포에서 평균 도달 단계를 유도한다.
	 *
	 * 응시자가 한 명도 없는 개념은 0단이 아니라 값 없음이다.
	 * 0단은 '1단도 통과하지 못했다'는 측정 결과이고 값 없음은 측정 자체가 없다는 뜻이라 서로 다르다.
	 */
	private BigDecimal averageReachedLevel(CohortComparisonQueryRepository.ConceptLevelRow row) {
		if (row.levelSum() == null || row.participantCount() == 0) {
			return null;
		}
		return row.levelSum().divide(
				BigDecimal.valueOf(row.participantCount()),
				CohortComparisonPolicy.LEVEL_SCALE,
				RoundingMode.HALF_UP
		);
	}

	private CohortComparisonResponse.ConceptChange toChange(
			CohortComparisonResponse.CohortConceptValue target,
			CohortComparisonResponse.CohortConceptValue baseline,
			CohortComparisonQueryRepository.ConceptLevelRow targetRow,
			CohortComparisonQueryRepository.ConceptLevelRow baselineRow
	) {
		NotComparableReason reason = notComparableReason(target, baseline, targetRow, baselineRow);
		if (reason != null) {
			return new CohortComparisonResponse.ConceptChange(ChangeDirection.NOT_COMPARABLE, null, reason);
		}
		// 화면에 보이는 두 평균을 그대로 빼서 표시값과 delta가 어긋나지 않게 한다.
		BigDecimal delta = target.averageReachedLevel().subtract(baseline.averageReachedLevel());
		return new CohortComparisonResponse.ConceptChange(
				CohortComparisonPolicy.directionOf(delta), delta, null);
	}

	private NotComparableReason notComparableReason(
			CohortComparisonResponse.CohortConceptValue target,
			CohortComparisonResponse.CohortConceptValue baseline,
			CohortComparisonQueryRepository.ConceptLevelRow targetRow,
			CohortComparisonQueryRepository.ConceptLevelRow baselineRow
	) {
		if (targetRow == null) {
			return NotComparableReason.ABSENT_IN_TARGET;
		}
		if (baselineRow == null) {
			return NotComparableReason.ABSENT_IN_BASELINE;
		}
		// 한쪽이 다른 개념으로 병합됐으면 두 기수의 개념이 같은 뜻이라는 전제가 깨진다.
		if (target.presence() == ConceptPresence.MERGED || baseline.presence() == ConceptPresence.MERGED) {
			return NotComparableReason.CONCEPT_MERGED;
		}
		if (CohortComparisonPolicy.blocksComparison(targetRow.aggregationStatus())
				|| CohortComparisonPolicy.blocksComparison(baselineRow.aggregationStatus())) {
			return NotComparableReason.AGGREGATION_UNAVAILABLE;
		}
		if (target.averageReachedLevel() == null || baseline.averageReachedLevel() == null) {
			return NotComparableReason.AGGREGATION_UNAVAILABLE;
		}
		return null;
	}

	private boolean versionChanged(
			CohortComparisonQueryRepository.ConceptLevelRow targetRow,
			CohortComparisonQueryRepository.ConceptLevelRow baselineRow
	) {
		if (targetRow == null || baselineRow == null) {
			return false;
		}
		Integer targetVersion = targetRow.curriculumVersionNo();
		Integer baselineVersion = baselineRow.curriculumVersionNo();
		return targetVersion != null && baselineVersion != null && !targetVersion.equals(baselineVersion);
	}

	/**
	 * 비교할 수 없는 행은 delta가 없어 어떤 정렬에서도 순서를 매길 수 없으므로 항상 뒤로 보내고
	 * 그 안에서는 검증 개념 순서를 따른다.
	 */
	private List<CohortComparisonResponse.ConceptComparison> sorted(
			List<CohortComparisonResponse.ConceptComparison> concepts,
			ComparisonSort sort
	) {
		Comparator<CohortComparisonResponse.ConceptComparison> conceptOrder = conceptOrder();
		if (sort == ComparisonSort.CONCEPT) {
			return concepts.stream().sorted(conceptOrder).toList();
		}

		Comparator<CohortComparisonResponse.ConceptComparison> byDelta =
				Comparator.comparing(concept -> concept.change().delta());
		if (sort == ComparisonSort.IMPROVED) {
			byDelta = byDelta.reversed();
		}
		Comparator<CohortComparisonResponse.ConceptComparison> rankedOrder =
				byDelta.thenComparing(CohortComparisonResponse.ConceptComparison::conceptName);

		List<CohortComparisonResponse.ConceptComparison> result = new ArrayList<>(concepts.stream()
				.filter(concept -> concept.change().delta() != null)
				.sorted(rankedOrder)
				.toList());
		result.addAll(concepts.stream()
				.filter(concept -> concept.change().delta() == null)
				.sorted(conceptOrder)
				.toList());
		return List.copyOf(result);
	}

	private Comparator<CohortComparisonResponse.ConceptComparison> conceptOrder() {
		return Comparator
				.comparing(
						(CohortComparisonResponse.ConceptComparison concept) -> concept.source().sectionSequenceNo(),
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(
						concept -> concept.source().pageStart(),
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(CohortComparisonResponse.ConceptComparison::conceptName)
				.thenComparing(concept -> concept.teachesId().toString());
	}

	private CohortComparisonResponse emptyResponse(
			CohortComparisonResponse.CohortRef target,
			CohortComparisonResponse.CohortRef baseline,
			List<CohortComparisonQueryRepository.BaselineCandidateRow> candidates,
			ComparisonEmptyState emptyState,
			ComparisonSort appliedSort
	) {
		return emptyResponse(target, baseline, candidates, emptyState, appliedSort, false);
	}

	private CohortComparisonResponse emptyResponse(
			CohortComparisonResponse.CohortRef target,
			CohortComparisonResponse.CohortRef baseline,
			List<CohortComparisonQueryRepository.BaselineCandidateRow> candidates,
			ComparisonEmptyState emptyState,
			ComparisonSort appliedSort,
			boolean sameCurriculumOnly
	) {
		return new CohortComparisonResponse(
				target,
				baseline,
				toOptions(candidates),
				emptyState,
				levelScale(),
				changeThreshold(),
				appliedSort,
				sameCurriculumOnly,
				List.of()
		);
	}

	private List<CohortComparisonResponse.BaselineOption> toOptions(
			List<CohortComparisonQueryRepository.BaselineCandidateRow> candidates
	) {
		return candidates.stream()
				.map(candidate -> new CohortComparisonResponse.BaselineOption(
						candidate.cohortId(), candidate.cohortName(), candidate.comparable()))
				.toList();
	}

	private CohortComparisonResponse.LevelScale levelScale() {
		return new CohortComparisonResponse.LevelScale(
				CohortComparisonPolicy.MIN_LEVEL,
				CohortComparisonPolicy.MAX_LEVEL,
				CohortComparisonPolicy.BAND_THRESHOLDS
		);
	}

	private CohortComparisonResponse.ChangeThreshold changeThreshold() {
		return new CohortComparisonResponse.ChangeThreshold(
				CohortComparisonPolicy.WORSENED_THRESHOLD,
				CohortComparisonPolicy.IMPROVED_THRESHOLD
		);
	}

	private Map<UUID, CohortComparisonQueryRepository.ConceptLevelRow> levelsOf(
			List<CohortComparisonQueryRepository.ConceptLevelRow> rows,
			UUID cohortId
	) {
		return rows.stream()
				.filter(row -> cohortId.equals(row.cohortId()))
				.collect(Collectors.toMap(
						CohortComparisonQueryRepository.ConceptLevelRow::teachesId,
						Function.identity(),
						(left, right) -> left,
						LinkedHashMap::new
				));
	}

	private Map<UUID, CohortComparisonQueryRepository.ConceptRoundRow> roundsOf(
			List<CohortComparisonQueryRepository.ConceptRoundRow> rows,
			UUID cohortId
	) {
		return rows.stream()
				.filter(row -> cohortId.equals(row.cohortId()))
				.collect(Collectors.toMap(
						CohortComparisonQueryRepository.ConceptRoundRow::teachesId,
						Function.identity(),
						(left, right) -> left,
						LinkedHashMap::new
				));
	}

}
