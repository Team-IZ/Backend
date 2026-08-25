package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.analytics.domain.CohortRiskComparison;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeLevel;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeSort;
import com.bigproject.backend.domain.analytics.domain.RoundAggregationStatus;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskTraineeRateResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiskTraineeAnalyticsService {
	// 빅프로젝트는 위험 판정식이 미니프로젝트와 달라(코드 대비 이해 부족·참여 저조 기여도 축) 같은 격자에 올리지 않는다.
	private static final String MINI_PROJECT = "MINI_PROJECT";
	private static final int RISK_RATE_SCALE = 4;

	private final AnalyticsActorGuard analyticsActorGuard;
	private final RiskTraineeQueryRepository riskTraineeQueryRepository;

	public RiskTraineeRateResponse findRiskTraineeRates(
			UUID cohortId,
			UUID projectId,
			List<UUID> classroomIds,
			Integer fromRoundNo,
			Integer toRoundNo,
			RiskTraineeLevel level,
			RiskTraineeSort sort,
			String actorEmail
	) {
		AuthUser actor = analyticsActorGuard.operatorOrManager(actorEmail, "매니저만 위험 교육생 비율을 조회할 수 있습니다.");
		RiskTraineeQueryRepository.CohortScope cohort = riskTraineeQueryRepository.findCohortScope(cohortId)
				.orElseThrow(() -> new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND));
		analyticsActorGuard.requireSameOrganization(cohort.organizationId(), actor);

		List<UUID> requestedClassroomIds = normalizeClassroomIds(classroomIds);
		for (UUID classroomId : requestedClassroomIds) {
			if (!riskTraineeQueryRepository.classroomBelongsToCohort(classroomId, cohortId, cohort.organizationId())) {
				throw new ApiException(AnalyticsErrorCode.CLASSROOM_NOT_IN_COHORT);
			}
		}
		if (projectId != null && !riskTraineeQueryRepository.projectBelongsToCohort(
				projectId, cohortId, cohort.organizationId(), MINI_PROJECT)) {
			throw new ApiException(AnalyticsErrorCode.PROJECT_NOT_IN_COHORT);
		}
		int from = fromRoundNo == null ? 1 : fromRoundNo;
		int to = toRoundNo == null ? Integer.MAX_VALUE : toRoundNo;
		if (from < 1 || to < from) {
			throw new ApiException(AnalyticsErrorCode.ROUND_RANGE_INVALID);
		}
		RiskTraineeSort appliedSort = sort == null ? RiskTraineeSort.RECENT_ROUND_WORST : sort;
		RiskTraineeLevel appliedLevel = level == null ? RiskTraineeLevel.CLASS : level;
		// 팀 번호는 반 안에서만 유일하고 team은 project_id 종속이라 둘 다 좁혀야 행이 성립한다.
		if (appliedLevel == RiskTraineeLevel.TEAM) {
			if (projectId == null) {
				throw new ApiException(AnalyticsErrorCode.TEAM_LEVEL_PROJECT_REQUIRED,
						"팀 계층은 프로젝트를 지정해야 합니다. 회차 번호가 프로젝트마다 1부터 다시 시작해 팀 추이를 이을 수 없습니다.");
			}
			if (requestedClassroomIds.size() != 1) {
				throw new ApiException(AnalyticsErrorCode.TEAM_LEVEL_SINGLE_CLASSROOM_REQUIRED,
						"팀 계층은 반을 하나만 지정해야 합니다. 팀 번호는 반 안에서만 유일합니다.");
			}
		}

		RiskTraineeQueryRepository.RoundCriteria criteria = new RiskTraineeQueryRepository.RoundCriteria(
				cohortId,
				cohort.organizationId(),
				MINI_PROJECT,
				projectId,
				from,
				to
		);
		List<RiskTraineeQueryRepository.RoundRow> rounds = riskTraineeQueryRepository.findRounds(criteria);
		int totalRegisteredRoundCount = riskTraineeQueryRepository.countRegisteredRounds(criteria);
		List<RiskTraineeQueryRepository.RiskCellRow> cells = riskTraineeQueryRepository.aggregateRiskCells(criteria);

		Map<UUID, RoundAggregationStatus> statusByRound = new LinkedHashMap<>();
		for (RiskTraineeQueryRepository.RoundRow round : rounds) {
			statusByRound.put(
					round.assessmentRoundId(),
					RoundAggregationStatus.from(round.roundStatus(), round.reportPublished())
			);
		}
		Map<UUID, RiskTraineeQueryRepository.RiskCellRow> cohortTotals = new LinkedHashMap<>();
		Map<UUID, Map<UUID, RiskTraineeQueryRepository.RiskCellRow>> classTotals = new LinkedHashMap<>();
		for (RiskTraineeQueryRepository.RiskCellRow cell : cells) {
			cohortTotals.merge(cell.assessmentRoundId(), cell, RiskTraineeAnalyticsService::sum);
			if (cell.classId() != null) {
				classTotals
						.computeIfAbsent(cell.classId(), ignored -> new LinkedHashMap<>())
						.merge(cell.assessmentRoundId(), cell, RiskTraineeAnalyticsService::sum);
			}
		}

		RiskTraineeQueryRepository.RosterCount cohortRoster =
				riskTraineeQueryRepository.findCohortRoster(cohortId, cohort.organizationId());
		Set<UUID> classroomFilter = Set.copyOf(requestedClassroomIds);

		// 기수 전체 행을 먼저 만들어 반 행의 색 판정 기준으로 쓴다.
		List<RiskTraineeRateResponse.RiskCell> cohortCells = toCells(rounds, statusByRound, cohortTotals, Map.of());
		Map<UUID, BigDecimal> cohortRateByRound = rateByRound(cohortCells);
		UUID recentAggregatedRoundId = recentAggregatedRoundId(rounds, statusByRound);

		List<RiskTraineeRateResponse.ClassRiskSummary> classSummaries = List.of();
		List<RiskTraineeRateResponse.TeamRiskSummary> teamSummaries = List.of();
		if (appliedLevel == RiskTraineeLevel.TEAM) {
			UUID selectedClassId = requestedClassroomIds.get(0);
			RiskTraineeQueryRepository.ClassRosterRow selectedClassRoster =
					riskTraineeQueryRepository.findClassRosters(cohortId, cohort.organizationId())
							.stream()
							.filter(roster -> roster.classId().equals(selectedClassId))
							.findFirst()
							.orElseThrow(() -> new ApiException(AcademicOperationsErrorCode.CLASSROOM_NOT_FOUND));
			// 팀 행은 기수가 아니라 소속 반 전체와 견준다. 그 기준 행을 classes에 함께 내려 화면 상단
			// '반 전체' 요약과 팀 비교 기준을 같은 값으로 통일한다.
			RiskTraineeRateResponse.ClassRiskSummary selectedClassSummary = toClassSummary(
					selectedClassRoster, classTotals, rounds, statusByRound, cohortRateByRound);
			Map<UUID, BigDecimal> classRateByRound = rateByRound(selectedClassSummary.cells());
			classSummaries = List.of(selectedClassSummary);
			teamSummaries = teamSummaries(
					criteria,
					selectedClassId,
					projectId,
					cohort.organizationId(),
					rounds,
					statusByRound,
					classRateByRound,
					recentAggregatedRoundId,
					appliedSort
			);
		} else {
			classSummaries = classSummaries(
					cohortId,
					cohort.organizationId(),
					classroomFilter,
					classTotals,
					rounds,
					statusByRound,
					cohortRateByRound,
					recentAggregatedRoundId,
					appliedSort
			);
		}

		return new RiskTraineeRateResponse(
				cohortId,
				MINI_PROJECT,
				projectId,
				totalRegisteredRoundCount,
				appliedSort,
				appliedLevel,
				rounds.stream()
						.map(round -> new RiskTraineeRateResponse.RoundColumn(
								round.assessmentRoundId(),
								round.roundNo(),
								round.cohortRoundNo(),
								round.roundName(),
								round.projectId(),
								round.projectName(),
								statusByRound.get(round.assessmentRoundId())
						))
						.toList(),
				new RiskTraineeRateResponse.CohortRiskSummary(
						cohortRoster.traineeCount(),
						cohortRoster.withdrawnCount(),
						exclusionRollup(cohortTotals),
						cohortCells
				),
				classSummaries,
				teamSummaries
		);
	}

	private List<RiskTraineeRateResponse.ClassRiskSummary> classSummaries(
			UUID cohortId,
			UUID organizationId,
			Set<UUID> classroomFilter,
			Map<UUID, Map<UUID, RiskTraineeQueryRepository.RiskCellRow>> classTotals,
			List<RiskTraineeQueryRepository.RoundRow> rounds,
			Map<UUID, RoundAggregationStatus> statusByRound,
			Map<UUID, BigDecimal> cohortRateByRound,
			UUID recentAggregatedRoundId,
			RiskTraineeSort appliedSort
	) {
		List<RiskTraineeRateResponse.ClassRiskSummary> summaries = new ArrayList<>();
		for (RiskTraineeQueryRepository.ClassRosterRow roster
				: riskTraineeQueryRepository.findClassRosters(cohortId, organizationId)) {
			if (!classroomFilter.isEmpty() && !classroomFilter.contains(roster.classId())) {
				continue;
			}
			summaries.add(toClassSummary(
					roster, classTotals, rounds, statusByRound, cohortRateByRound));
		}
		summaries.sort(comparator(
				appliedSort,
				recentAggregatedRoundId,
				cohortRateByRound,
				RiskTraineeRateResponse.ClassRiskSummary::className,
				RiskTraineeRateResponse.ClassRiskSummary::exclusionRollup,
				RiskTraineeRateResponse.ClassRiskSummary::cells
		));
		return summaries;
	}

	private RiskTraineeRateResponse.ClassRiskSummary toClassSummary(
			RiskTraineeQueryRepository.ClassRosterRow roster,
			Map<UUID, Map<UUID, RiskTraineeQueryRepository.RiskCellRow>> classTotals,
			List<RiskTraineeQueryRepository.RoundRow> rounds,
			Map<UUID, RoundAggregationStatus> statusByRound,
			Map<UUID, BigDecimal> cohortRateByRound
	) {
		Map<UUID, RiskTraineeQueryRepository.RiskCellRow> totals =
				classTotals.getOrDefault(roster.classId(), Map.of());
		return new RiskTraineeRateResponse.ClassRiskSummary(
				roster.classId(),
				roster.className(),
				roster.traineeCount(),
				roster.withdrawnCount(),
				exclusionRollup(totals),
				roster.managerNames(),
				toCells(rounds, statusByRound, totals, cohortRateByRound)
		);
	}

	/**
	 * 팀 행은 명단에서 시작한다. 한 회차도 수행하지 않은 팀도 격자에 빈 행으로 남아야
	 * '팀이 없는 것'과 '아직 결과가 없는 것'이 섞이지 않는다.
	 *
	 * baselineRateByRound는 팀이 견주는 기준이며, 기수 전체가 아니라 이 팀이 속한 반 전체의
	 * 회차별 비율이다. 반 안에서만 팀을 비교할 수 있으므로(팀 번호가 반 안에서만 유일) 기수
	 * 전체와 비교하면 반 자체가 기수보다 나쁠 때 모든 팀이 항상 WORSE로만 보이는 문제가 있다.
	 */
	private List<RiskTraineeRateResponse.TeamRiskSummary> teamSummaries(
			RiskTraineeQueryRepository.RoundCriteria criteria,
			UUID classroomId,
			UUID projectId,
			UUID organizationId,
			List<RiskTraineeQueryRepository.RoundRow> rounds,
			Map<UUID, RoundAggregationStatus> statusByRound,
			Map<UUID, BigDecimal> baselineRateByRound,
			UUID recentAggregatedRoundId,
			RiskTraineeSort appliedSort
	) {
		Map<UUID, Map<UUID, RiskTraineeQueryRepository.RiskCellRow>> teamTotals = new LinkedHashMap<>();
		for (RiskTraineeQueryRepository.TeamRiskCellRow cell
				: riskTraineeQueryRepository.aggregateTeamRiskCells(criteria, classroomId)) {
			if (cell.teamId() == null) {
				continue;
			}
			teamTotals
					.computeIfAbsent(cell.teamId(), ignored -> new LinkedHashMap<>())
					.merge(cell.assessmentRoundId(), asRiskCellRow(cell), RiskTraineeAnalyticsService::sum);
		}

		List<RiskTraineeRateResponse.TeamRiskSummary> summaries = new ArrayList<>();
		for (RiskTraineeQueryRepository.TeamRosterRow roster
				: riskTraineeQueryRepository.findTeamRosters(projectId, classroomId, organizationId)) {
			Map<UUID, RiskTraineeQueryRepository.RiskCellRow> totals =
					teamTotals.getOrDefault(roster.teamId(), Map.of());
			summaries.add(new RiskTraineeRateResponse.TeamRiskSummary(
					roster.teamId(),
					roster.teamNumber(),
					roster.teamName(),
					roster.classId(),
					roster.className(),
					roster.memberCount(),
					exclusionRollup(totals),
					toCells(rounds, statusByRound, totals, baselineRateByRound)
			));
		}
		summaries.sort(comparator(
				appliedSort,
				recentAggregatedRoundId,
				baselineRateByRound,
				RiskTraineeRateResponse.TeamRiskSummary::teamNumber,
				RiskTraineeRateResponse.TeamRiskSummary::exclusionRollup,
				RiskTraineeRateResponse.TeamRiskSummary::cells
		));
		return summaries;
	}

	private static RiskTraineeQueryRepository.RiskCellRow asRiskCellRow(
			RiskTraineeQueryRepository.TeamRiskCellRow cell
	) {
		return new RiskTraineeQueryRepository.RiskCellRow(
				cell.assessmentRoundId(),
				cell.teamId(),
				cell.eligibleCount(),
				cell.riskCount(),
				cell.notAttendedCount(),
				cell.sessionIncompleteCount(),
				cell.invalidAttemptCount(),
				cell.observedRiskCount()
		);
	}

	/**
	 * baselineRateByRound가 비어 있으면 견줄 대상이 없어 색 판정을 남기지 않는다.
	 * 반 행은 기수 전체 비율을, 팀 행은 소속 반 전체 비율을 기준으로 받는다.
	 */
	private List<RiskTraineeRateResponse.RiskCell> toCells(
			List<RiskTraineeQueryRepository.RoundRow> rounds,
			Map<UUID, RoundAggregationStatus> statusByRound,
			Map<UUID, RiskTraineeQueryRepository.RiskCellRow> totals,
			Map<UUID, BigDecimal> baselineRateByRound
	) {
		List<RiskTraineeRateResponse.RiskCell> result = new ArrayList<>();
		for (RiskTraineeQueryRepository.RoundRow round : rounds) {
			RoundAggregationStatus status = statusByRound.get(round.assessmentRoundId());
			RiskTraineeQueryRepository.RiskCellRow total = totals.get(round.assessmentRoundId());
			long eligibleCount = total == null ? 0 : total.eligibleCount();
			long riskCount = total == null ? 0 : total.riskCount();
			long observedRiskCount = total == null ? 0 : total.observedRiskCount();
			BigDecimal rate = riskRate(status, eligibleCount, riskCount);
			result.add(new RiskTraineeRateResponse.RiskCell(
					round.assessmentRoundId(),
					round.roundNo(),
					round.cohortRoundNo(),
					status,
					eligibleCount,
					riskCount,
					observedRiskCount,
					rate,
					comparison(rate, baselineRateByRound.get(round.assessmentRoundId())),
					new RiskTraineeRateResponse.ExclusionBreakdown(
							total == null ? 0 : total.notAttendedCount(),
							total == null ? 0 : total.sessionIncompleteCount(),
							total == null ? 0 : total.invalidAttemptCount()
					)
			));
		}
		return result;
	}

	/**
	 * 같은 회차의 기준 비율과 단순 비교한다. 완충 구간을 두지 않는다.
	 * 척도가 다른 값을 비교해도 되도록 compareTo가 아니라 부호만 본다.
	 */
	private CohortRiskComparison comparison(BigDecimal rate, BigDecimal baselineRate) {
		if (rate == null || baselineRate == null) {
			return null;
		}
		int direction = rate.compareTo(baselineRate);
		if (direction > 0) {
			return CohortRiskComparison.WORSE;
		}
		return direction < 0 ? CohortRiskComparison.BETTER : CohortRiskComparison.SAME;
	}

	/**
	 * cells에 담긴 riskRate를 회차 ID 기준으로 뽑아낸다. 기수 전체 행·선택된 반 행 어느 쪽이든
	 * 같은 방식으로 기준 비율 맵을 만들 수 있도록 공용으로 둔다.
	 */
	private Map<UUID, BigDecimal> rateByRound(List<RiskTraineeRateResponse.RiskCell> cells) {
		Map<UUID, BigDecimal> rateByRound = new LinkedHashMap<>();
		for (RiskTraineeRateResponse.RiskCell cell : cells) {
			if (cell.riskRate() != null) {
				rateByRound.put(cell.assessmentRoundId(), cell.riskRate());
			}
		}
		return rateByRound;
	}

	/**
	 * 화면의 '채점에서 빠진 사람' 열은 회차마다가 아니라 행마다 한 벌이다.
	 * 조회 범위의 모든 회차를 유형별로 합산한다. 한 회차만 보면 그 회차에 마침 미집계가 없던 반이
	 * 앞 회차에서 계속 빠졌던 반보다 나아 보여, EXCLUSION_COUNT 정렬이 누적 이탈을 못 짚는다.
	 */
	private RiskTraineeRateResponse.ExclusionBreakdown exclusionRollup(
			Map<UUID, RiskTraineeQueryRepository.RiskCellRow> totals
	) {
		long notAttended = 0;
		long sessionIncomplete = 0;
		long invalidAttempt = 0;
		for (RiskTraineeQueryRepository.RiskCellRow total : totals.values()) {
			notAttended += total.notAttendedCount();
			sessionIncomplete += total.sessionIncompleteCount();
			invalidAttempt += total.invalidAttemptCount();
		}
		return new RiskTraineeRateResponse.ExclusionBreakdown(notAttended, sessionIncomplete, invalidAttempt);
	}

	/**
	 * 회차 열은 최근 프로젝트부터 내림차순이므로 목록 앞쪽에서 처음 만나는 발행 회차가 최근 회차다.
	 */
	private UUID recentAggregatedRoundId(
			List<RiskTraineeQueryRepository.RoundRow> rounds,
			Map<UUID, RoundAggregationStatus> statusByRound
	) {
		for (RiskTraineeQueryRepository.RoundRow round : rounds) {
			RoundAggregationStatus status = statusByRound.get(round.assessmentRoundId());
			if (status != null && status.readable()) {
				return round.assessmentRoundId();
			}
		}
		return null;
	}

	/**
	 * 네 정렬 기준 모두 조립된 응답 값만으로 계산하므로 반 행과 팀 행이 같은 비교자를 쓴다.
	 * 값이 없는 행은 항상 뒤로 보내고, 마지막 비교는 이름으로 고정해 순서를 결정적으로 만든다.
	 * baselineRateByRound는 반 행이면 기수 전체, 팀 행이면 소속 반 전체의 회차별 비율이다.
	 */
	private <T> Comparator<T> comparator(
			RiskTraineeSort sort,
			UUID recentAggregatedRoundId,
			Map<UUID, BigDecimal> baselineRateByRound,
			Function<T, String> name,
			Function<T, RiskTraineeRateResponse.ExclusionBreakdown> rollup,
			Function<T, List<RiskTraineeRateResponse.RiskCell>> cells
	) {
		Comparator<T> byName = Comparator.comparing(name, Comparator.nullsLast(Comparator.naturalOrder()));
		return switch (sort) {
			case NAME -> byName;
			case EXCLUSION_COUNT -> Comparator
					.comparingLong((T row) -> rollup.apply(row).total())
					.reversed()
					.thenComparing(byName);
			case WORSE_ROUND_COUNT -> Comparator
					.comparingLong((T row) -> cells.apply(row).stream()
							.filter(cell -> cell.comparisonToCohort() == CohortRiskComparison.WORSE)
							.count())
					.reversed()
					.thenComparing(byName);
			case RECENT_ROUND_WORST -> Comparator
					.comparing(
							(T row) -> gapAtRecentRound(
									cells.apply(row), recentAggregatedRoundId, baselineRateByRound),
							Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(byName);
		};
	}

	/**
	 * 최근 발행 회차에서 기준 비율을 얼마나 웃도는지. 값이 없으면 null로 두어 뒤로 밀린다.
	 */
	private BigDecimal gapAtRecentRound(
			List<RiskTraineeRateResponse.RiskCell> cells,
			UUID recentAggregatedRoundId,
			Map<UUID, BigDecimal> baselineRateByRound
	) {
		if (recentAggregatedRoundId == null) {
			return null;
		}
		BigDecimal baselineRate = baselineRateByRound.get(recentAggregatedRoundId);
		if (baselineRate == null) {
			return null;
		}
		return cells.stream()
				.filter(cell -> recentAggregatedRoundId.equals(cell.assessmentRoundId()))
				.map(RiskTraineeRateResponse.RiskCell::riskRate)
				.filter(Objects::nonNull)
				.findFirst()
				.map(rate -> rate.subtract(baselineRate))
				.orElse(null);
	}

	/**
	 * 집계 전 회차와 분모가 0인 회차는 0%가 아니라 값 없음으로 돌려준다.
	 * 화면이 '집계 전'·'시작 전'을 위험자 0%와 같은 색으로 읽으면 안 되기 때문이다.
	 */
	private BigDecimal riskRate(RoundAggregationStatus status, long eligibleCount, long riskCount) {
		if (!status.readable() || eligibleCount == 0) {
			return null;
		}
		return BigDecimal.valueOf(riskCount)
				.divide(BigDecimal.valueOf(eligibleCount), RISK_RATE_SCALE, RoundingMode.HALF_UP);
	}

	private static RiskTraineeQueryRepository.RiskCellRow sum(
			RiskTraineeQueryRepository.RiskCellRow left,
			RiskTraineeQueryRepository.RiskCellRow right
	) {
		return new RiskTraineeQueryRepository.RiskCellRow(
				left.assessmentRoundId(),
				left.classId(),
				left.eligibleCount() + right.eligibleCount(),
				left.riskCount() + right.riskCount(),
				left.notAttendedCount() + right.notAttendedCount(),
				left.sessionIncompleteCount() + right.sessionIncompleteCount(),
				left.invalidAttemptCount() + right.invalidAttemptCount(),
				left.observedRiskCount() + right.observedRiskCount()
		);
	}

	private List<UUID> normalizeClassroomIds(List<UUID> classroomIds) {
		if (classroomIds == null) {
			return List.of();
		}
		return classroomIds.stream().filter(Objects::nonNull).distinct().toList();
	}

}
