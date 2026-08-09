package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.ClassRiskRow;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.CohortReportHeader;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.CohortScale;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.ConceptDistributionRow;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.CurriculumSummaryRow;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.ExcludedRow;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.GroupShortfallRow;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.RoundConceptRow;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.TopStudentRoundRow;
import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository.TopStudentRow;
import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;
import com.bigproject.backend.domain.reporting.domain.ReportException;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse.ClassRiskRate;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse.ConceptDiagnosis;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse.Excluded;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse.GroupShortfall;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse.ReachDistribution;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse.RoundDiagnosis;
import com.bigproject.backend.domain.reporting.presentation.dto.CohortDiagnosisResponse.TopStudent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OP-05 조립. 스냅샷 두 벌을 문서 하나로 합친다.
 *
 * <p>여기서도 판정이 아니라 <b>번역</b>만 한다 — 위험자 수·저성과 여부·도달 분포는 전부
 * {@code report_metric}에 이미 적재된 값이다. 다만 뷰가 (개념 × 도달 단계) 행으로 주기 때문에
 * 개념 단위로 접는 일은 여기서 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CohortReportServiceImpl implements CohortReportService {

	/** 2단 이하 = 0·1·2단. DB {@code low_level_count = reached_level IN (0,1,2)}와 같은 정의다. */
	private static final int BELOW_LEVEL_2_MAX = 2;

	/**
	 * 반별 위험자 표의 기준선 행 이름. <b>화면이 이 문자열로 기준선을 찾는다</b>
	 * (Frontend {@code operator/report/_/components/ClassOps.tsx}:
	 * {@code classRisk.find(c => c.className === '기수 전체')}).
	 */
	private static final String OVERALL_CLASS_LABEL = "기수 전체";

	private final CohortReportQueryRepository queryRepository;

	@Override
	@Transactional(readOnly = true)
	public CohortDiagnosisResponse findClassDiagnosis(UUID cohortId) {
		CohortReportHeader header = queryRepository.findHeader(cohortId)
				.orElseThrow(() -> new ReportException(ReportErrorCode.COHORT_REPORT_NOT_FOUND));

		// 수업 진단 스냅샷이 없으면 문서 자체가 아직 없는 것이다. 빈 문서를 그리면
		// 오퍼레이터가 "회차는 끝났는데 왜 비었지"를 먼저 묻게 된다.
		if (header.diagnosisSnapshotId() == null) {
			throw new ReportException(ReportErrorCode.COHORT_REPORT_NOT_FOUND);
		}

		CohortScale scale = queryRepository.findScale(cohortId);
		List<CurriculumSummaryRow> summaries = queryRepository.findCurriculumSummaries(header.diagnosisSnapshotId());
		List<RoundConceptRow> roundConcepts = queryRepository.findRoundConcepts(header.diagnosisSnapshotId());

		Map<String, List<String>> roundNamesByConcept = roundNamesByConcept(roundConcepts);
		List<ConceptDiagnosis> concepts = toConcepts(
				queryRepository.findConceptDistribution(header.diagnosisSnapshotId()), roundNamesByConcept);
		List<RoundDiagnosis> rounds = toRounds(roundConcepts);

		// 결산 스냅샷이 있어야 우수 교육생·반 위험·집단 미달이 확정된다.
		// 프론트 목업도 CONFIRMED에서만 이 셋을 채운다 — 없는 회차의 결과를 미리 보여주지 않기 위해서다.
		boolean confirmed = header.outcomeSnapshotId() != null && header.outcomePublishedAt() != null;

		List<TopStudent> topStudents = confirmed ? toTopStudents(header.outcomeSnapshotId()) : List.of();
		List<ClassRiskRate> classRisk = confirmed ? toClassRisk(header.outcomeSnapshotId()) : List.of();
		List<GroupShortfall> groupShortfalls = confirmed ? toGroupShortfalls(header.outcomeSnapshotId()) : List.of();

		int gradedCount = summaries.stream().mapToInt(CurriculumSummaryRow::assessedCount).max().orElse(0);
		int eligibleCount = summaries.stream().mapToInt(CurriculumSummaryRow::eligibleCount).max().orElse(0);

		// 요약 탭의 `전체 응시 − 미응시 − 무효 − 중단 = 채점` 줄이 쓰는 값이다.
		// 예전에는 Excluded.empty()를 하드코딩해서 뺄셈이 화면에서 맞지 않았다.
		ExcludedRow excluded = queryRepository.findExcluded(header.diagnosisSnapshotId());

		return new CohortDiagnosisResponse(
				confirmed ? "CONFIRMED" : "IN_PROGRESS",
				header.cohortName(),
				confirmed ? iso(header.outcomePublishedAt()) : null,
				header.completedRoundCount(),
				header.requiredRoundCount(),
				iso(header.startDate()),
				confirmed ? iso(header.endDate()) : null,
				scale.traineeCount(),
				scale.classCount(),
				concepts.size(),
				(int) summaries.stream().map(CurriculumSummaryRow::curriculumVersionId).distinct().count(),
				gradedCount,
				eligibleCount,
				new Excluded(excluded.notTaken(), excluded.invalid(), excluded.interrupted()),
				concepts,
				rounds,
				topStudents,
				lastCompletedRoundLabel(roundConcepts),
				classRisk,
				groupShortfalls
		);
	}

	/**
	 * 개념 → 그 개념이 쓰인 회차 이름들. 회차 뷰가 (회차 × 개념 × 단계) 행이라 개념 이름으로 접는다.
	 * 개념 id가 아니라 <b>이름</b>으로 묶는 이유는 분포 뷰가 teaches_id를, 회차 뷰가
	 * project_verification_concept_id를 쓰기 때문이다 — 두 축을 잇는 공통 키가 이름뿐이다.
	 */
	private static Map<String, List<String>> roundNamesByConcept(List<RoundConceptRow> rows) {
		Map<String, Set<String>> collected = new LinkedHashMap<>();
		for (RoundConceptRow row : rows) {
			if (row.conceptName() == null || row.roundName() == null) {
				continue;
			}
			collected.computeIfAbsent(row.conceptName(), key -> new LinkedHashSet<>()).add(row.roundName());
		}
		return collected.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue()),
						(a, b) -> a, LinkedHashMap::new));
	}

	private static List<ConceptDiagnosis> toConcepts(
			List<ConceptDistributionRow> rows,
			Map<String, List<String>> roundNamesByConcept
	) {
		// (개념 × 도달 단계) 행을 개념 단위로 접는다. teaches_id가 키다.
		Map<UUID, List<ConceptDistributionRow>> byConcept = rows.stream()
				.filter(row -> row.teachesId() != null)
				.collect(Collectors.groupingBy(ConceptDistributionRow::teachesId, LinkedHashMap::new, Collectors.toList()));

		List<ConceptDiagnosis> result = new ArrayList<>();
		for (Map.Entry<UUID, List<ConceptDistributionRow>> entry : byConcept.entrySet()) {
			List<ConceptDistributionRow> group = entry.getValue();
			ConceptDistributionRow head = group.get(0);

			ReachDistribution distribution = distribution(group,
					ConceptDistributionRow::reachedLevel, ConceptDistributionRow::participantCount);

			int graded = group.stream().mapToInt(ConceptDistributionRow::participantCount).sum();
			int total = group.stream()
					.map(ConceptDistributionRow::denominator)
					.filter(java.util.Objects::nonNull)
					.mapToInt(Integer::intValue)
					.max()
					.orElse(graded);

			result.add(new ConceptDiagnosis(
					entry.getKey().toString(),
					head.conceptName(),
					head.curriculumName(),
					versionLabel(head.curriculumVersionNo()),
					sectionLabel(head.sectionTitle(), head.sectionPageStart(), head.sectionPageEnd()),
					roundNamesByConcept.getOrDefault(head.conceptName(), List.of()),
					distribution,
					graded,
					total,
					belowLevel2(distribution)
			));
		}
		return result;
	}

	private static List<RoundDiagnosis> toRounds(List<RoundConceptRow> rows) {
		Map<UUID, List<RoundConceptRow>> byRound = rows.stream()
				.filter(row -> row.roundId() != null)
				.collect(Collectors.groupingBy(RoundConceptRow::roundId, LinkedHashMap::new, Collectors.toList()));

		List<RoundDiagnosis> result = new ArrayList<>();
		for (Map.Entry<UUID, List<RoundConceptRow>> entry : byRound.entrySet()) {
			List<RoundConceptRow> group = entry.getValue();
			RoundConceptRow head = group.get(0);

			ReachDistribution distribution = distribution(group,
					row -> row.reachedLevel() == null ? -1 : row.reachedLevel(),
					RoundConceptRow::participantCount);

			int graded = group.stream().mapToInt(RoundConceptRow::participantCount).sum();
			int total = group.stream()
					.map(RoundConceptRow::denominator)
					.filter(java.util.Objects::nonNull)
					.mapToInt(Integer::intValue)
					.max()
					.orElse(graded);

			List<String> conceptNames = group.stream()
					.map(RoundConceptRow::conceptName)
					.filter(java.util.Objects::nonNull)
					.distinct()
					.toList();

			result.add(new RoundDiagnosis(
					entry.getKey().toString(),
					head.roundName(),
					conceptNames,
					distribution,
					graded,
					total,
					belowLevel2(distribution)
			));
		}
		return result;
	}

	/**
	 * 단계별 행을 분포 하나로 접는다.
	 *
	 * <p>단계가 0~4 밖이면(null이거나 미정의) {@code unasked}로 보낸다 — 그 학생 코드에 개념이
	 * 없어 문항이 안 만들어진 경우다. <b>0단으로 넣으면 안 된다.</b> 0단은 물었는데 못한 것이다.
	 */
	private static <T> ReachDistribution distribution(
			List<T> rows,
			java.util.function.ToIntFunction<T> levelOf,
			java.util.function.ToIntFunction<T> countOf
	) {
		int[] buckets = new int[5];
		int unasked = 0;
		for (T row : rows) {
			int level = levelOf.applyAsInt(row);
			int count = countOf.applyAsInt(row);
			if (level >= 0 && level <= 4) {
				buckets[level] += count;
			} else {
				unasked += count;
			}
		}
		return new ReachDistribution(buckets[0], buckets[1], buckets[2], buckets[3], buckets[4], unasked);
	}

	/** 2단 이하 인원 = 0단 + 1단 + 2단. unasked는 포함하지 않는다. */
	private static int belowLevel2(ReachDistribution distribution) {
		return distribution.level0() + distribution.level1() + distribution.level2();
	}

	private List<TopStudent> toTopStudents(UUID outcomeSnapshotId) {
		Map<UUID, List<Integer>> roundsByUser = queryRepository.findTopStudentRounds(outcomeSnapshotId).stream()
				.collect(Collectors.groupingBy(TopStudentRoundRow::userId, LinkedHashMap::new,
						Collectors.mapping(TopStudentRoundRow::roundNo, Collectors.toList())));

		return queryRepository.findTopStudents(outcomeSnapshotId).stream()
				.map(row -> new TopStudent(
						row.userId().toString(),
						row.name(),
						row.className(),
						row.topCount(),
						roundsByUser.getOrDefault(row.userId(), List.of())
				))
				.toList();
	}

	/**
	 * 반별 위험자 비율. <b>기준선 행이 반드시 하나 있어야 한다.</b>
	 *
	 * <p>화면이 이 목록에서 {@code className}이 정확히 {@code "기수 전체"}인 행을 찾아 기준선으로
	 * 쓴다(Frontend {@code DiagnosisSummary.tsx}·{@code ClassOps.tsx}가 둘 다 문자열 일치로 찾는다).
	 * 그 행이 없으면 <b>에러 없이 조용히</b> 요약 탭의 "위험자는 기수 전체 N%" 줄과 반·집단 탭의
	 * 기준선 막대가 사라진다 — 화면이 깨지지 않아서 더 늦게 발견된다.
	 *
	 * <p>{@code operator_cohort_class_risk_view}가 이 집계 행을 내려주면 그대로 쓰고, 없으면
	 * 여기서 합계를 만들어 붙인다. 뷰가 주는 값을 덮어쓰지 않는 이유는 스냅샷이 권위이기
	 * 때문이다 — 뷰의 집계 기준과 여기서 더한 합이 다를 수 있고, 그때 믿을 것은 뷰다.
	 *
	 * <p>⚠️ 한글 리터럴에 계약이 걸려 있다. 반 이름이 바뀌거나 실제로 `기수 전체`라는 반이
	 * 생기면 깨진다. 중장기적으로는 {@code isOverall} 플래그를 응답에 추가해 이름 의존을
	 * 없애는 편이 안전하다(프론트 타입도 함께 바뀌어야 해서 이번 범위에는 넣지 않았다).
	 */
	private List<ClassRiskRate> toClassRisk(UUID outcomeSnapshotId) {
		List<ClassRiskRate> rows = queryRepository.findClassRisks(outcomeSnapshotId).stream()
				.map(row -> new ClassRiskRate(row.className(), row.traineeCount(), row.atRiskCount()))
				.toList();

		if (rows.isEmpty() || rows.stream().anyMatch(row -> OVERALL_CLASS_LABEL.equals(row.className()))) {
			return rows;
		}

		log.info("기수 전체 기준선 행이 뷰에 없어 합계로 생성한다: snapshotId={}, 반 {}개",
				outcomeSnapshotId, rows.size());

		List<ClassRiskRate> withOverall = new ArrayList<>();
		withOverall.add(new ClassRiskRate(
				OVERALL_CLASS_LABEL,
				rows.stream().mapToInt(ClassRiskRate::traineeCount).sum(),
				rows.stream().mapToInt(ClassRiskRate::atRiskCount).sum()
		));
		withOverall.addAll(rows);
		return withOverall;
	}

	private List<GroupShortfall> toGroupShortfalls(UUID outcomeSnapshotId) {
		return queryRepository.findGroupShortfalls(outcomeSnapshotId).stream()
				.map(row -> new GroupShortfall(
						row.conceptName(),
						row.curriculumName(),
						sectionLabel(row.sectionTitle(), row.sectionPageStart(), row.sectionPageEnd()),
						// 이 지표의 grain이 CLASS_CONCEPT라 회차 축이 없다(DTO javadoc 참고).
						"",
						row.className(),
						row.shortfallCount(),
						row.totalCount()
				))
				.toList();
	}

	/**
	 * 반별 위험자 비율의 기준이 된 회차. 마지막 회차를 쓴다 —
	 * 화면이 `미프 7차 기준`처럼 표기한다.
	 */
	private static String lastCompletedRoundLabel(List<RoundConceptRow> rows) {
		return rows.stream()
				.map(RoundConceptRow::roundName)
				.filter(java.util.Objects::nonNull)
				.reduce((first, second) -> second)
				.orElse("");
	}

	/** {@code 'v' + version_no}. 프론트 목 데이터가 `v2`·`v1` 형식이다. */
	private static String versionLabel(Integer versionNo) {
		return versionNo == null ? null : "v" + versionNo;
	}

	/**
	 * {@code 제목 (p.시작–끝)}. 프론트 목 데이터가 `네트워킹 (p.28–40)` 형식이고
	 * 구분자는 하이픈이 아니라 <b>en-dash(U+2013)</b>다.
	 *
	 * <p>표시 문자열을 서버가 만드는 것은 프론트의 "표시 라벨은 화면 것" 원칙과 어긋나지만,
	 * {@code section}이 문자열 한 필드라 계약상 달리 방법이 없다. 계약을
	 * {@code {title, pageStart, pageEnd}}로 바꾸면 이 메서드는 사라진다.
	 */
	private static String sectionLabel(String title, Integer pageStart, Integer pageEnd) {
		if (title == null) {
			return null;
		}
		if (pageStart == null || pageEnd == null) {
			return title;
		}
		return title + " (p." + pageStart + "–" + pageEnd + ")";
	}

	private static String iso(Instant instant) {
		return instant == null ? null : instant.toString();
	}

	private static String iso(LocalDate date) {
		return date == null ? null : date.toString();
	}
}
