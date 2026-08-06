package com.bigproject.backend.domain.analytics.application;

import com.bigproject.backend.domain.analytics.domain.CohortRiskComparison;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeSort;
import com.bigproject.backend.domain.analytics.domain.RoundAggregationStatus;
import com.bigproject.backend.domain.analytics.presentation.dto.RiskTraineeRateResponse;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.application.EmailNormalizer;
import com.bigproject.backend.domain.member.domain.Role;
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
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiskTraineeAnalyticsService {
	private static final String ACTIVE = "ACTIVE";
	// 빅프로젝트는 위험 판정식이 미니프로젝트와 달라(코드 대비 이해 부족·참여 저조 기여도 축) 같은 격자에 올리지 않는다.
	private static final String MINI_PROJECT = "MINI_PROJECT";
	private static final int RISK_RATE_SCALE = 4;

	private final AuthUserRepository authUserRepository;
	private final RiskTraineeQueryRepository riskTraineeQueryRepository;

	public RiskTraineeRateResponse findRiskTraineeRates(
			UUID cohortId,
			UUID projectId,
			List<UUID> classroomIds,
			Integer fromRoundNo,
			Integer toRoundNo,
			RiskTraineeSort sort,
			String actorEmail
	) {
		AuthUser actor = activeActor(actorEmail);
		if (actor.role() != Role.OPERATOR && actor.role() != Role.MANAGER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "매니저만 위험 교육생 비율을 조회할 수 있습니다.");
		}
		RiskTraineeQueryRepository.CohortScope cohort = riskTraineeQueryRepository.findCohortScope(cohortId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기수를 찾을 수 없습니다."));
		if (!cohort.organizationId().equals(actor.organizationId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 기관의 기수는 조회할 수 없습니다.");
		}

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
		List<RiskTraineeQueryRepository.ClassRosterRow> classRosters =
				riskTraineeQueryRepository.findClassRosters(cohortId, cohort.organizationId());
		Set<UUID> classroomFilter = Set.copyOf(requestedClassroomIds);

		// 기수 전체 행을 먼저 만들어 반 행의 색 판정 기준으로 쓴다.
		List<RiskTraineeRateResponse.RiskCell> cohortCells = toCells(rounds, statusByRound, cohortTotals, Map.of());
		Map<UUID, BigDecimal> cohortRateByRound = new LinkedHashMap<>();
		for (RiskTraineeRateResponse.RiskCell cell : cohortCells) {
			if (cell.riskRate() != null) {
				cohortRateByRound.put(cell.assessmentRoundId(), cell.riskRate());
			}
		}
		UUID recentAggregatedRoundId = recentAggregatedRoundId(rounds, statusByRound);

		List<RiskTraineeRateResponse.ClassRiskSummary> classSummaries = new ArrayList<>();
		for (RiskTraineeQueryRepository.ClassRosterRow roster : classRosters) {
			if (!classroomFilter.isEmpty() && !classroomFilter.contains(roster.classId())) {
				continue;
			}
			Map<UUID, RiskTraineeQueryRepository.RiskCellRow> totals =
					classTotals.getOrDefault(roster.classId(), Map.of());
			classSummaries.add(new RiskTraineeRateResponse.ClassRiskSummary(
					roster.classId(),
					roster.className(),
					roster.traineeCount(),
					roster.withdrawnCount(),
					exclusionRollup(totals, recentAggregatedRoundId),
					toCells(rounds, statusByRound, totals, cohortRateByRound)
			));
		}
		classSummaries.sort(comparator(appliedSort, recentAggregatedRoundId, cohortRateByRound));

		return new RiskTraineeRateResponse(
				cohortId,
				MINI_PROJECT,
				projectId,
				totalRegisteredRoundCount,
				appliedSort,
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
				classSummaries
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
	 * 네 정렬 기준 모두 조립된 응답 값만으로 계산한다.
	 * 값이 없는 반은 항상 뒤로 보내고, 마지막 비교는 이름으로 고정해 순서를 결정적으로 만든다.
	 */
	private Comparator<RiskTraineeRateResponse.ClassRiskSummary> comparator(
			RiskTraineeSort sort,
			UUID recentAggregatedRoundId,
			Map<UUID, BigDecimal> cohortRateByRound
	) {
		Comparator<RiskTraineeRateResponse.ClassRiskSummary> byName =
				Comparator.comparing(RiskTraineeRateResponse.ClassRiskSummary::className,
						Comparator.nullsLast(Comparator.naturalOrder()));
		return switch (sort) {
			case NAME -> byName;
			case EXCLUSION_COUNT -> Comparator
					.comparingLong((RiskTraineeRateResponse.ClassRiskSummary summary) ->
							summary.exclusionRollup().total())
					.reversed()
					.thenComparing(byName);
			case WORSE_ROUND_COUNT -> Comparator
					.comparingLong((RiskTraineeRateResponse.ClassRiskSummary summary) -> summary.cells().stream()
							.filter(cell -> cell.comparisonToCohort() == CohortRiskComparison.WORSE)
							.count())
					.reversed()
					.thenComparing(byName);
			case RECENT_ROUND_WORST -> Comparator
					.comparing(
							(RiskTraineeRateResponse.ClassRiskSummary summary) ->
									gapAtRecentRound(summary, recentAggregatedRoundId, cohortRateByRound),
							Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(byName);
		};
	}

	/**
	 * 최근 발행 회차에서 기수 전체 비율을 얼마나 웃도는지. 값이 없으면 null로 두어 뒤로 밀린다.
	 */
	private BigDecimal gapAtRecentRound(
			RiskTraineeRateResponse.ClassRiskSummary summary,
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
		return summary.cells().stream()
				.filter(cell -> recentAggregatedRoundId.equals(cell.assessmentRoundId()))
				.map(RiskTraineeRateResponse.RiskCell::riskRate)
				.filter(java.util.Objects::nonNull)
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
		return classroomIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
	}

	private AuthUser activeActor(String email) {
		AuthUser actor = authUserRepository.findByNormalizedEmail(EmailNormalizer.normalize(email))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 사용자를 찾을 수 없습니다."));
		if (!ACTIVE.equals(actor.status()) || !actor.emailVerified()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 사용자만 분석 정보를 조회할 수 있습니다.");
		}
		if (actor.role() != Role.SUPER_ADMIN && !ACTIVE.equals(actor.organizationStatus())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 기관의 사용자만 분석 정보를 조회할 수 있습니다.");
		}
		return actor;
	}
}
