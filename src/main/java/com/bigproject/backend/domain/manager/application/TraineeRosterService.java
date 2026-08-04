package com.bigproject.backend.domain.manager.application;

import com.bigproject.backend.domain.academicoperations.domain.Cohort;
import com.bigproject.backend.domain.academicoperations.domain.RoundConceptView;
import com.bigproject.backend.domain.academicoperations.infrastructure.CohortRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ManagerAssignmentRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ProjectRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ProjectVerificationConceptRepository;
import com.bigproject.backend.domain.assessment.domain.MiniProjectRoundView;
import com.bigproject.backend.domain.assessment.infrastructure.ProjectAssessmentRoundRepository;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.manager.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.manager.domain.TraineeRosterSortMode;
import com.bigproject.backend.domain.manager.domain.TraineeRoundRowResultStatus;
import com.bigproject.backend.domain.manager.presentation.dto.ConceptResultItemResponse;
import com.bigproject.backend.domain.manager.presentation.dto.TraineeRosterContextResponse;
import com.bigproject.backend.domain.manager.presentation.dto.TraineeRosterItemResponse;
import com.bigproject.backend.domain.manager.presentation.dto.TraineeRosterListResponse;
import com.bigproject.backend.domain.member.application.EmailNormalizer;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * MG-05 교육생 명부(매니저 담당 반) 조회.
 *
 * <p>회차 확정·검증 개념 정의·담당 반 범위·기수 조회는 Spring Data JPA 리포지토리를 사용하고, 측정·평가 원천을
 * 가로지르는 명부 행·개념 도달·우수 누적 집계만 {@link TraineeRosterRepository}(JdbcTemplate 구현)에 위임한다.
 *
 * <p>범위 밖 구현 사항(응답에는 반영하지 않음, 후속 과제):
 * <ul>
 *   <li>과거 회차 조회 시 회차 당시 반·팀 소속 스냅샷 대신 현재 소속을 사용한다(시간여행 미지원).</li>
 *   <li>"우수" 판정은 원본 와이어프레임 정의서가 참조하는 반 단위 순위 정책(9-3: 상위 1~2명, 3문제 중 최저값 비교)이
 *       이번 요구사항 문서에 포함돼 있지 않아, 선택 회차의 검증 개념 3건을 모두 채점(GENERATED)받고 전부 최고 단계(L4)에
 *       도달했는지를 임시 기준으로 사용한다. 위험 5개 유형 중 회차 간 추이·기여도 스냅샷이 필요한 STAGE_DECLINE,
 *       PERSISTENT_LOW, CONTRIBUTION_UNDERSTANDING_GAP, LOW_PARTICIPATION은 구현하지 않았다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraineeRosterService {
	private static final String ACTIVE = "ACTIVE";
	private static final int EXPECTED_CONCEPT_COUNT = 3;

	private final AuthUserRepository authUserRepository;
	private final CohortRepository cohortRepository;
	private final ManagerAssignmentRepository managerAssignmentRepository;
	private final ProjectRepository projectRepository;
	private final ProjectAssessmentRoundRepository projectAssessmentRoundRepository;
	private final ProjectVerificationConceptRepository projectVerificationConceptRepository;
	private final TraineeRosterRepository traineeRosterRepository;

	public TraineeRosterListResponse findRoster(
			UUID cohortId,
			UUID classId,
			AccountStatus accountStatus,
			UUID assessmentRoundId,
			TraineeRosterSortMode sortMode,
			String query,
			int page,
			int size,
			String actorEmail
	) {
		AuthUser actor = activeManager(actorEmail);
		validatePage(page, size);

		Cohort cohort = cohortRepository.findByCohortIdAndDeletedAtIsNull(cohortId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기수를 찾을 수 없습니다."));
		if (!cohort.getOrgId().equals(actor.organizationId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 기관의 교육생 명부는 조회할 수 없습니다.");
		}

		List<UUID> accessibleClassIds = managerAssignmentRepository.findAccessibleClassIds(
				actor.userId(), actor.organizationId()
		);
		if (accessibleClassIds.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "담당 배정된 반이 없습니다.");
		}
		if (classId != null && !accessibleClassIds.contains(classId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "담당하지 않는 반입니다.");
		}
		List<UUID> effectiveClassIds = classId != null ? List.of(classId) : accessibleClassIds;

		SelectedRound round = resolveRound(cohortId, assessmentRoundId);

		TraineeRosterRepository.Criteria criteria = new TraineeRosterRepository.Criteria(
				cohortId,
				actor.organizationId(),
				effectiveClassIds,
				accountStatus,
				normalizeQuery(query)
		);

		List<RoundConceptView> conceptDefinitions =
				projectVerificationConceptRepository.findRoundConcepts(round.conceptSetId());

		RosterResult rosterResult = (sortMode == null || sortMode == TraineeRosterSortMode.DEFAULT)
				? loadDefaultSorted(criteria, round, conceptDefinitions, page, size)
				: loadMetricSorted(criteria, round, conceptDefinitions, sortMode, page, size);

		TraineeRosterRepository.AccountStatusCounts counts = traineeRosterRepository.countAccountStatuses(
				cohortId, actor.organizationId(), accessibleClassIds
		);

		TraineeRosterContextResponse context = new TraineeRosterContextResponse(
				cohortId,
				round.projectId(),
				round.assessmentRoundId(),
				round.roundNo(),
				round.roundName(),
				round.analysisSequenceNo(),
				accessibleClassIds.size(),
				classId,
				counts.totalCount(),
				counts.activeCount(),
				counts.invitationPendingCount(),
				counts.inactiveCount(),
				rosterResult.totalElements()
		);

		return new TraineeRosterListResponse(
				rosterResult.items(),
				page,
				size,
				rosterResult.totalElements(),
				totalPages(rosterResult.totalElements(), size),
				context
		);
	}

	private AuthUser activeManager(String email) {
		AuthUser actor = authUserRepository.findByNormalizedEmail(EmailNormalizer.normalize(email))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 사용자를 찾을 수 없습니다."));
		if (!ACTIVE.equals(actor.status()) || !actor.emailVerified()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 사용자만 교육생 명부를 조회할 수 있습니다.");
		}
		if (!ACTIVE.equals(actor.organizationStatus())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 기관의 사용자만 교육생 명부를 조회할 수 있습니다.");
		}
		if (actor.role() != Role.MANAGER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "매니저만 교육생 명부를 조회할 수 있습니다.");
		}
		return actor;
	}

	private SelectedRound resolveRound(UUID cohortId, UUID assessmentRoundId) {
		MiniProjectRoundView round;
		if (assessmentRoundId == null) {
			round = projectAssessmentRoundRepository.findLatestMiniProjectRounds(cohortId, Limit.of(1)).stream()
					.findFirst()
					.orElseThrow(() -> new ResponseStatusException(
							HttpStatus.NOT_FOUND, "조회 가능한 미니프로젝트 회차가 없습니다."));
		} else {
			round = projectAssessmentRoundRepository.findMiniProjectRound(assessmentRoundId)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다."));
			if (!round.cohortId().equals(cohortId)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "해당 기수에 속한 회차가 아닙니다.");
			}
		}
		if (round.conceptSetId() == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "검증 개념 세트가 확정되지 않은 회차입니다.");
		}
		int analysisSequenceNo = projectRepository.countMiniProjectAnalysisSequenceNo(
				round.cohortId(), round.projectSequenceNo(), round.projectId()
		);
		return new SelectedRound(
				round.assessmentRoundId(),
				round.projectId(),
				round.conceptSetId(),
				round.roundNo(),
				round.roundName(),
				analysisSequenceNo
		);
	}

	private RosterResult loadDefaultSorted(
			TraineeRosterRepository.Criteria criteria,
			SelectedRound round,
			List<RoundConceptView> conceptDefinitions,
			int page,
			int size
	) {
		TraineeRosterRepository.Page<TraineeRosterRepository.RosterMemberRow> pageResult =
				traineeRosterRepository.findRosterPage(criteria, round.assessmentRoundId(), page, size);
		List<UUID> userIds = pageResult.content().stream().map(TraineeRosterRepository.RosterMemberRow::userId).toList();

		Map<UUID, List<TraineeRosterRepository.ConceptResultRow>> conceptsByUser = groupConcepts(
				traineeRosterRepository.findConceptResults(userIds, round.conceptSetId(), round.assessmentRoundId())
		);
		Map<UUID, Integer> excellentCounts = toOccurrenceMap(
				traineeRosterRepository.findExcellentOccurrenceCounts(userIds, criteria.cohortId(), round.analysisSequenceNo())
		);

		List<TraineeRosterItemResponse> items = pageResult.content().stream()
				.map(row -> toItemResponse(
						row,
						summarize(conceptDefinitions, conceptsByUser.getOrDefault(row.userId(), List.of())),
						excellentCounts.getOrDefault(row.userId(), 0)
				))
				.toList();
		return new RosterResult(items, pageResult.totalElements());
	}

	private RosterResult loadMetricSorted(
			TraineeRosterRepository.Criteria criteria,
			SelectedRound round,
			List<RoundConceptView> conceptDefinitions,
			TraineeRosterSortMode sortMode,
			int page,
			int size
	) {
		List<TraineeRosterRepository.RosterMemberRow> all =
				traineeRosterRepository.findAllRosterMembers(criteria, round.assessmentRoundId());
		List<UUID> allUserIds = all.stream().map(TraineeRosterRepository.RosterMemberRow::userId).toList();

		Map<UUID, List<TraineeRosterRepository.ConceptResultRow>> conceptsByUser = groupConcepts(
				traineeRosterRepository.findConceptResults(allUserIds, round.conceptSetId(), round.assessmentRoundId())
		);
		Map<UUID, Integer> excellentCounts = toOccurrenceMap(
				traineeRosterRepository.findExcellentOccurrenceCounts(allUserIds, criteria.cohortId(), round.analysisSequenceNo())
		);

		List<ScoredMember> scored = all.stream()
				.map(row -> new ScoredMember(
						row,
						summarize(conceptDefinitions, conceptsByUser.getOrDefault(row.userId(), List.of())),
						excellentCounts.getOrDefault(row.userId(), 0)
				))
				.sorted(metricComparator(sortMode))
				.toList();

		long total = scored.size();
		int fromIndex = Math.min(page * size, scored.size());
		int toIndex = Math.min(fromIndex + size, scored.size());
		List<TraineeRosterItemResponse> items = scored.subList(fromIndex, toIndex).stream()
				.map(s -> toItemResponse(s.row(), s.summary(), s.excellentOccurrenceCount()))
				.toList();
		return new RosterResult(items, total);
	}

	private Comparator<ScoredMember> metricComparator(TraineeRosterSortMode sortMode) {
		Comparator<ScoredMember> nameTiebreak = Comparator
				.<ScoredMember, String>comparing(s -> normalizedNameKey(s.row()))
				.thenComparing(s -> s.row().userId());
		if (sortMode == TraineeRosterSortMode.EXCELLENT_OCCURRENCE_COUNT_DESC) {
			return Comparator.<ScoredMember>comparingInt(ScoredMember::excellentOccurrenceCount)
					.reversed()
					.thenComparing(nameTiebreak);
		}
		return Comparator.<ScoredMember, Integer>comparing(
						s -> s.summary().lowStageConceptCount(),
						Comparator.nullsLast(Comparator.reverseOrder())
				)
				.thenComparing(nameTiebreak);
	}

	private String normalizedNameKey(TraineeRosterRepository.RosterMemberRow row) {
		String key = (row.name() == null || row.name().isBlank()) ? row.email() : row.name();
		return key.toLowerCase(Locale.ROOT);
	}

	private Map<UUID, List<TraineeRosterRepository.ConceptResultRow>> groupConcepts(
			List<TraineeRosterRepository.ConceptResultRow> rows
	) {
		Map<UUID, List<TraineeRosterRepository.ConceptResultRow>> result = new LinkedHashMap<>();
		for (TraineeRosterRepository.ConceptResultRow row : rows) {
			result.computeIfAbsent(row.userId(), ignored -> new ArrayList<>()).add(row);
		}
		return result;
	}

	private Map<UUID, Integer> toOccurrenceMap(List<TraineeRosterRepository.ExcellentOccurrenceRow> rows) {
		Map<UUID, Integer> result = new HashMap<>();
		for (TraineeRosterRepository.ExcellentOccurrenceRow row : rows) {
			result.put(row.userId(), row.occurrenceCount());
		}
		return result;
	}

	private ConceptSummary summarize(
			List<RoundConceptView> definitions,
			List<TraineeRosterRepository.ConceptResultRow> results
	) {
		Map<UUID, TraineeRosterRepository.ConceptResultRow> byConceptId = new HashMap<>();
		for (TraineeRosterRepository.ConceptResultRow result : results) {
			byConceptId.put(result.conceptId(), result);
		}

		List<ConceptResultItemResponse> items = new ArrayList<>();
		int scoredCount = 0;
		int lowStageCount = 0;
		boolean anyScored = false;
		boolean allAtMaxLevel = true;
		for (RoundConceptView definition : definitions) {
			TraineeRosterRepository.ConceptResultRow result = byConceptId.get(definition.conceptId());
			String generationStatus = result == null ? null : result.generationStatus();
			Integer highestReachedLevel = result == null ? null : result.highestReachedLevel();
			items.add(new ConceptResultItemResponse(
					definition.conceptId(), definition.sequenceNo(), definition.conceptName(),
					generationStatus, highestReachedLevel
			));
			if ("GENERATED".equals(generationStatus)) {
				anyScored = true;
				scoredCount++;
				int level = highestReachedLevel == null ? 0 : highestReachedLevel;
				if (level <= 2) {
					lowStageCount++;
				}
				if (level < 4) {
					allAtMaxLevel = false;
				}
			} else {
				allAtMaxLevel = false;
			}
		}
		Integer lowStage = anyScored ? lowStageCount : null;
		boolean excellentThisRound = anyScored && scoredCount == EXPECTED_CONCEPT_COUNT && allAtMaxLevel;
		return new ConceptSummary(items, scoredCount, lowStage, excellentThisRound);
	}

	private TraineeRosterItemResponse toItemResponse(
			TraineeRosterRepository.RosterMemberRow row,
			ConceptSummary summary,
			int excellentOccurrenceCount
	) {
		return new TraineeRosterItemResponse(
				row.cohortMemberId(),
				row.userId(),
				row.name(),
				row.email(),
				apiAccountStatus(row.appUserStatus(), row.appUserDeleted()),
				row.classId(),
				row.className(),
				rowResultStatus(row),
				summary.concepts(),
				EXPECTED_CONCEPT_COUNT,
				summary.scoredConceptCount(),
				summary.lowStageConceptCount(),
				summary.excellentThisRound(),
				excellentOccurrenceCount
		);
	}

	private TraineeRoundRowResultStatus rowResultStatus(TraineeRosterRepository.RosterMemberRow row) {
		if (row.attemptId() == null) {
			return TraineeRoundRowResultStatus.NO_ATTEMPT;
		}
		if ("CONFIRMED_INVALID".equals(row.validityReviewStatus())) {
			return TraineeRoundRowResultStatus.INVALID;
		}
		return TraineeRoundRowResultStatus.valueOf(row.attemptStatus());
	}

	private AccountStatus apiAccountStatus(String databaseStatus, boolean deleted) {
		if (deleted) {
			return AccountStatus.INACTIVE;
		}
		return switch (databaseStatus) {
			case "PENDING" -> AccountStatus.INVITED;
			case "ACTIVE" -> AccountStatus.ACTIVE;
			case "LOCKED" -> AccountStatus.LOCKED;
			case "INACTIVE" -> AccountStatus.INACTIVE;
			default -> throw new IllegalStateException("지원하지 않는 계정 상태입니다: " + databaseStatus);
		};
	}

	private void validatePage(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지 범위가 올바르지 않습니다.");
		}
	}

	private String normalizeQuery(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		return query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private int totalPages(long totalElements, int size) {
		return totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
	}

	// 선택된 회차와 화면 표시용 분석 순서를 함께 담는다.
	private record SelectedRound(
			UUID assessmentRoundId,
			UUID projectId,
			UUID conceptSetId,
			int roundNo,
			String roundName,
			int analysisSequenceNo
	) {
	}

	private record RosterResult(List<TraineeRosterItemResponse> items, long totalElements) {
	}

	private record ConceptSummary(
			List<ConceptResultItemResponse> concepts,
			int scoredConceptCount,
			Integer lowStageConceptCount,
			boolean excellentThisRound
	) {
	}

	private record ScoredMember(
			TraineeRosterRepository.RosterMemberRow row,
			ConceptSummary summary,
			int excellentOccurrenceCount
	) {
	}
}
