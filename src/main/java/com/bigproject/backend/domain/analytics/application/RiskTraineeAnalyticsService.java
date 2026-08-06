package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.CohortRiskComparison;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeLevel;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeSort;
import com.bigproject.backend.domain.analytics.domain.RoundAggregationStatus;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskTraineeRateResponse;
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
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기수를 찾을 수 없습니다."));
		analyticsActorGuard.requireSameOrganization(cohort.organizationId(), actor);

		List<UUID> requestedClassroomIds = normalizeClassroomIds(classroomIds);
		for (UUID classroomId : requestedClassroomIds) {
			if (!riskTraineeQueryRepository.classroomBelongsToCohort(classroomId, cohortId, cohort.organizationId())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "해당 기수에 속한 반이 아닙니다.");
			}
		}
		if (projectId != null && !riskTraineeQueryRepository.projectBelongsToCohort(
				projectId, cohortId, cohort.organizationId(), MINI_PROJECT)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "해당 기수의 미니프로젝트가 아닙니다.");
		}
		int from = fromRoundNo == null ? 1 : fromRoundNo;
		int to = toRoundNo == null ? Integer.MAX_VALUE : toRoundNo;
		if (from < 1 || to < from) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회차 범위가 올바르지 않습니다.");
		}
		RiskTraineeSort appliedSort = sort == null ? RiskTraineeSort.RECENT_ROUND_WORST : sort;
		RiskTraineeLevel appliedLevel = level == null ? RiskTraineeLevel.CLASS : level;
		// 팀 번호는 반 안에서만 유일하고 team은 project_id 종속이라 둘 다 좁혀야 행이 성립한다.
		if (appliedLevel == RiskTraineeLevel.TEAM) {
			if (projectId == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"팀 계층은 프로젝트를 지정해야 합니다. 회차 번호가 프로젝트마다 1부터 다시 시작해 팀 추이를 이을 수 없습니다.");
			}
			if (requestedClassroomIds.size() != 1) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
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

		// 기수 전체 행을 먼저 만들어 반·팀 행의 색 판정 기준으로 쓴다.
		List<RiskTraineeRateResponse.RiskCell> cohortCells = toCells(rounds, statusByRound, cohortTotals, Map.of());
		Map<UUID, BigDecimal> cohortRateByRound = new LinkedHashMap<>();
		for (RiskTraineeRateResponse.RiskCell cell : cohortCells) {
			if (cell.riskRate() != null) {
				cohortRateByRound.put(cell.assessmentRoundId(), cell.riskRate());
			}
		}
		UUID recentAggregatedRoundId = recentAggregatedRoundId(rounds, statusByRound);

		List<RiskTraineeRateResponse.ClassRiskSummary> classSummaries = List.of();
		List<RiskTraineeRateResponse.TeamRiskSummary> teamSummaries = List.of();
		if (appliedLevel == RiskTraineeLevel.TEAM) {
			teamSummaries = teamSummaries(
					criteria,
					requestedClassroomIds.get(0),
					projectId,
					cohort.organizationId(),
					rounds,
					statusByRound,
					cohortRateByRound,
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
								round.roundName(),
								round.projectId(),
								round.projectName(),
								statusByRound.get(round.assessmentRoundId())
						))
						.toList(),
				new RiskTraineeRateResponse.CohortRiskSummary(
						cohortRoster.traineeCount(),
						cohortRoster.withdrawnCount(),
						exclusionRollup(cohortTotals, recentAggregatedRoundId),
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
			Map<UUID, RiskTraineeQueryRepository.RiskCellRow> totals =
					classTotals.getOrDefault(roster.classId(), Map.of());
			summaries.add(new RiskTraineeRateResponse.ClassRiskSummary(
					roster.classId(),
					roster.className(),
					roster.traineeCount(),
					roster.withdrawnCount(),
					exclusionRollup(totals, recentAggregatedRoundId),
					toCells(rounds, statusByRound, totals, cohortRateByRound)
			));
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

	/**
	 * 팀 행은 명단에서 시작한다. 한 회차도 수행하지 않은 팀도 격자에 빈 행으로 남아야
	 * '팀이 없는 것'과 '아직 결과가 없는 것'이 섞이지 않는다.
	 */
	private List<RiskTraineeRateResponse.TeamRiskSummary> teamSummaries(
			RiskTraineeQueryRepository.RoundCriteria criteria,
			UUID classroomId,
			UUID projectId,
			UUID organizationId,
			List<RiskTraineeQueryRepository.RoundRow> rounds,
			Map<UUID, RoundAggregationStatus> statusByRound,
			Map<UUID, BigDecimal> cohortRateByRound,
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
					exclusionRollup(totals, recentAggregatedRoundId),
					toCells(rounds, statusByRound, totals, cohortRateByRound)
			));
		}
		summaries.sort(comparator(
				appliedSort,
				recentAggregatedRoundId,
				cohortRateByRound,
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
				cell.invalidAttemptCount()
		);
	}

	/**
	 * cohortRateByRound가 비어 있으면 기수 전체 행이라 견줄 대상이 없어 색 판정을 남기지 않는다.
	 */
	private List<RiskTraineeRateResponse.RiskCell> toCells(
			List<RiskTraineeQueryRepository.RoundRow> rounds,
			Map<UUID, RoundAggregationStatus> statusByRound,
			Map<UUID, RiskTraineeQueryRepository.RiskCellRow> totals,
			Map<UUID, BigDecimal> cohortRateByRound
	) {
		List<RiskTraineeRateResponse.RiskCell> result = new ArrayList<>();
		for (RiskTraineeQueryRepository.RoundRow round : rounds) {
			RoundAggregationStatus status = statusByRound.get(round.assessmentRoundId());
			RiskTraineeQueryRepository.RiskCellRow total = totals.get(round.assessmentRoundId());
			long eligibleCount = total == null ? 0 : total.eligibleCount();
			long riskCount = total == null ? 0 : total.riskCount();
			BigDecimal rate = riskRate(status, eligibleCount, riskCount);
			result.add(new RiskTraineeRateResponse.RiskCell(
					round.assessmentRoundId(),
					round.roundNo(),
					status,
					eligibleCount,
					riskCount,
					rate,
					comparison(rate, cohortRateByRound.get(round.assessmentRoundId())),
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
	 * 같은 회차의 기수 전체 비율과 단순 비교한다. 완충 구간을 두지 않는다.
	 * 척도가 다른 값을 비교해도 되도록 compareTo가 아니라 부호만 본다.
	 */
	private CohortRiskComparison comparison(BigDecimal rate, BigDecimal cohortRate) {
		if (rate == null || cohortRate == null) {
			return null;
		}
		int direction = rate.compareTo(cohortRate);
		if (direction > 0) {
			return CohortRiskComparison.WORSE;
		}
		return direction < 0 ? CohortRiskComparison.BETTER : CohortRiskComparison.SAME;
	}

	/**
	 * 화면의 '채점에서 빠진 사람' 열은 회차마다가 아니라 행마다 한 벌이다.
	 * 기준 회차는 최근 발행 회차로 고정해 기본 정렬(RECENT_ROUND_WORST)이 보는 회차와 일치시킨다.
	 */
	private RiskTraineeRateResponse.ExclusionBreakdown exclusionRollup(
			Map<UUID, RiskTraineeQueryRepository.RiskCellRow> totals,
			UUID recentAggregatedRoundId
	) {
		RiskTraineeQueryRepository.RiskCellRow total =
				recentAggregatedRoundId == null ? null : totals.get(recentAggregatedRoundId);
		if (total == null) {
			return new RiskTraineeRateResponse.ExclusionBreakdown(0, 0, 0);
		}
		return new RiskTraineeRateResponse.ExclusionBreakdown(
				total.notAttendedCount(),
				total.sessionIncompleteCount(),
				total.invalidAttemptCount()
		);
	}

	private UUID recentAggregatedRoundId(
			List<RiskTraineeQueryRepository.RoundRow> rounds,
			Map<UUID, RoundAggregationStatus> statusByRound
	) {
		UUID recent = null;
		for (RiskTraineeQueryRepository.RoundRow round : rounds) {
			RoundAggregationStatus status = statusByRound.get(round.assessmentRoundId());
			if (status != null && status.readable()) {
				recent = round.assessmentRoundId();
			}
		}
		return recent;
	}

	/**
	 * 네 정렬 기준 모두 조립된 응답 값만으로 계산하므로 반 행과 팀 행이 같은 비교자를 쓴다.
	 * 값이 없는 행은 항상 뒤로 보내고, 마지막 비교는 이름으로 고정해 순서를 결정적으로 만든다.
	 */
	private <T> Comparator<T> comparator(
			RiskTraineeSort sort,
			UUID recentAggregatedRoundId,
			Map<UUID, BigDecimal> cohortRateByRound,
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
									cells.apply(row), recentAggregatedRoundId, cohortRateByRound),
							Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(byName);
		};
	}

	/**
	 * 최근 발행 회차에서 기수 전체 비율을 얼마나 웃도는지. 값이 없으면 null로 두어 뒤로 밀린다.
	 */
	private BigDecimal gapAtRecentRound(
			List<RiskTraineeRateResponse.RiskCell> cells,
			UUID recentAggregatedRoundId,
			Map<UUID, BigDecimal> cohortRateByRound
	) {
		if (recentAggregatedRoundId == null) {
			return null;
		}
		BigDecimal cohortRate = cohortRateByRound.get(recentAggregatedRoundId);
		if (cohortRate == null) {
			return null;
		}
		return cells.stream()
				.filter(cell -> recentAggregatedRoundId.equals(cell.assessmentRoundId()))
				.map(RiskTraineeRateResponse.RiskCell::riskRate)
				.filter(Objects::nonNull)
				.findFirst()
				.map(rate -> rate.subtract(cohortRate))
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
				left.invalidAttemptCount() + right.invalidAttemptCount()
		);
	}

	private List<UUID> normalizeClassroomIds(List<UUID> classroomIds) {
		if (classroomIds == null) {
			return List.of();
		}
		return classroomIds.stream().filter(Objects::nonNull).distinct().toList();
	}

}
