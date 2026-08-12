package com.bigproject.backend.domain.analytics.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 기수 간 검증 개념 비교 원천 조회 포트.
 *
 * 테이블 정의서 v07에는 두 기수를 한 격자에 올리는 View가 없다. 관련 View는 모두 grain이
 * 단일 기수의 발행 스냅샷(snapshot_id)이고 ReportSnapshot은 기수 하나만 소유하므로
 * 두 스냅샷을 teaches_id로 잇는 일은 애플리케이션 몫이다. 원천은 다음과 같다.
 * - 평균 도달 단계: report_metric(section_code='CONCEPT_REACH_DISTRIBUTION',
 *   metric_grain_code='CONCEPT_REACHED_LEVEL')의 L0~L4 분포에서 유도한다.
 *   이 grain은 teaches_id를 직접 갖지만 회차 축이 없다.
 * - 회차 라벨: report_metric(section_code='ROUND_CONCEPT_RESULT')이며 이쪽은 개념 키가
 *   project_verification_concept_id라 teaches_id를 얻으려면 한 단계 더 조인해야 한다.
 *   report_metric의 CHECK 제약이 두 개념 키 중 정확히 하나만 채우도록 강제하기 때문이다.
 * - 출처 표기: curriculum_teaches_mapping → curriculum_section(sequence_no='4장'),
 *   curriculum_version(version_no), curriculum_material(title)
 *
 * teaches와 curriculum_material은 cohort_id가 없는 기관 단위 테이블이라 기수를 넘어 안정적이며
 * 화면 문구 '같은 개념이라 두 기수에서 뜻이 같다'가 이 성질에 기댄다.
 */
public interface CohortComparisonQueryRepository {

	Optional<CohortRow> findCohort(UUID cohortId);

	/** 비교 드롭다운 후보. 같은 기관의 다른 기수를 최근 시작 순으로 돌려준다. */
	List<BaselineCandidateRow> findBaselineCandidates(UUID organizationId, UUID excludeCohortId);

	/** 발행된 수업 진단 리포트의 활성 스냅샷이 있는지 확인한다. */
	boolean hasPublishedDiagnosis(UUID cohortId, UUID organizationId);

	/**
	 * 비교에 쓰는 스냅샷의 완전성을 기수별로 돌려준다.
	 *
	 * PARTIAL은 리포트 생성 실패가 아니라 미응시·무효·중단으로 모수에서 빠진 응시 건이
	 * 있다는 뜻이다. 그래서 집계에서 빼지 않고 화면이 구분해 표시할 수 있게 값만 올린다.
	 * 스냅샷이 없는 기수는 행 자체가 없다.
	 */
	List<SnapshotCompletionRow> findSnapshotCompletion(UUID organizationId, List<UUID> cohortIds);

	/** 두 기수의 개념별 도달 단계 합계·인원을 한 번의 질의로 집계한다. */
	List<ConceptLevelRow> aggregateConceptLevels(UUID organizationId, List<UUID> cohortIds);

	/** 개념이 검증된 회차 라벨. 여러 회차에 걸친 개념은 가장 최근 회차 한 건만 돌려준다. */
	List<ConceptRoundRow> findConceptRounds(UUID organizationId, List<UUID> cohortIds);

	record CohortRow(UUID cohortId, String cohortName, UUID organizationId) {
	}

	record BaselineCandidateRow(UUID cohortId, String cohortName, boolean comparable) {
	}

	/**
	 * 한 기수의 활성 진단 스냅샷 완전성.
	 *
	 * sampleCount는 채점된 응시 건수, missingCount는 미응시·무효·중단으로 빠진 응시 건수다.
	 * 둘 다 교육생 수가 아니라 <b>응시 건수</b> 단위다.
	 */
	record SnapshotCompletionRow(UUID cohortId, String completionStatus, long sampleCount, long missingCount) {
	}

	/**
	 * 한 기수·한 개념의 원시 집계.
	 *
	 * 평균을 SQL에서 나누지 않고 분자(levelSum)와 분모(participantCount)를 그대로 올린다.
	 * 분모가 0일 때 0단이 아니라 값 없음으로 처리하는 규칙을 서비스 한 곳에 두기 위해서다.
	 */
	record ConceptLevelRow(
			UUID cohortId,
			UUID teachesId,
			String conceptName,
			String teachesStatus,
			UUID mergedIntoTeachesId,
			BigDecimal levelSum,
			long participantCount,
			long missingCount,
			String aggregationStatus,
			UUID curriculumVersionId,
			Integer curriculumVersionNo,
			String curriculumTitle,
			Integer sectionSequenceNo,
			String sectionTitle,
			Integer pageStart,
			Integer pageEnd
	) {
	}

	record ConceptRoundRow(UUID cohortId, UUID teachesId, Integer roundNo, String roundName) {
	}
}
