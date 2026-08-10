package com.bigproject.backend.domain.analytics.infrastructure;

import com.bigproject.backend.domain.analytics.domain.CohortComparisonQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcCohortComparisonQueryRepository implements CohortComparisonQueryRepository {

	// 수업 진단 리포트의 발행된 활성 스냅샷. 기수당 한 건만 남긴다.
	// 같은 기수에 활성 스냅샷이 둘 이상 남아 있어도 분포 합계가 두 배로 계상되지 않도록
	// DISTINCT ON으로 최신 한 건을 고정한다.
	//
	// completion_status는 여기서 거르지 않고 그대로 올린다. 이 값의 PARTIAL은 "리포트 생성이
	// 일부 실패했다"가 아니라 "미응시·무효·중단이 있어 모수에서 빠진 응시 건이 있다"는 뜻이라,
	// 걸러내면 그런 기수가 비교 대상에서 통째로 사라진다(미응시가 0인 기수는 거의 없다).
	// 합의 문서 결정 11 — 발행하되 구분해서 알린다. 교육생 화면과 analytics가 같은 원칙이다.
	private static final String ACTIVE_SNAPSHOT_CTE = """
			WITH active_snapshot AS (
				SELECT DISTINCT ON (rpt.cohort_id)
					rs.snapshot_id,
					rpt.cohort_id,
					rs.completion_status,
					rs.sample_count,
					rs.missing_count
				FROM report rpt
				JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
				WHERE rpt.org_id = ?
					AND rpt.report_type = 'COHORT_CURRICULUM_DIAGNOSIS'
					AND rpt.lifecycle_status = 'ACTIVE'
					AND rpt.published_at IS NOT NULL
					AND rpt.cohort_id IN (%s)
				ORDER BY rpt.cohort_id, rs.as_of_at DESC, rs.snapshot_version DESC
			)
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<CohortRow> findCohort(UUID cohortId) {
		return jdbcTemplate.query(
				"SELECT cohort_id, name, org_id FROM cohort WHERE cohort_id = ? AND deleted_at IS NULL",
				(rs, rowNum) -> new CohortRow(
						rs.getObject("cohort_id", UUID.class),
						rs.getString("name"),
						rs.getObject("org_id", UUID.class)
				),
				cohortId
		).stream().findFirst();
	}

	/**
	 * cohort에는 기수 번호 컬럼이 없고 표시명(name)만 있으므로 시작일 기준 최근 순으로 내린다.
	 * comparable은 그 기수에 읽을 수 있는 발행 스냅샷이 있는지이며, 없는 기수를 고르면
	 * 격자가 비므로 드롭다운에서 미리 구분할 수 있게 함께 내려준다.
	 */
	@Override
	public List<BaselineCandidateRow> findBaselineCandidates(UUID organizationId, UUID excludeCohortId) {
		return jdbcTemplate.query(
				"""
				SELECT
					c.cohort_id,
					c.name,
					EXISTS (
						SELECT 1
						FROM report rpt
						JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
						WHERE rpt.cohort_id = c.cohort_id
							AND rpt.org_id = c.org_id
							AND rpt.report_type = 'COHORT_CURRICULUM_DIAGNOSIS'
							AND rpt.lifecycle_status = 'ACTIVE'
							AND rpt.published_at IS NOT NULL
					) AS comparable
				FROM cohort c
				WHERE c.org_id = ?
					AND c.cohort_id <> ?
					AND c.deleted_at IS NULL
				ORDER BY c.start_date DESC, c.cohort_id
				""",
				(rs, rowNum) -> new BaselineCandidateRow(
						rs.getObject("cohort_id", UUID.class),
						rs.getString("name"),
						rs.getBoolean("comparable")
				),
				organizationId,
				excludeCohortId
		);
	}

	@Override
	public boolean hasPublishedDiagnosis(UUID cohortId, UUID organizationId) {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM report rpt
				JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
				WHERE rpt.cohort_id = ?
					AND rpt.org_id = ?
					AND rpt.report_type = 'COHORT_CURRICULUM_DIAGNOSIS'
					AND rpt.lifecycle_status = 'ACTIVE'
					AND rpt.published_at IS NOT NULL
				""",
				Integer.class,
				cohortId,
				organizationId
		);
		return count != null && count > 0;
	}

	/**
	 * 비교에 쓰는 두 기수의 스냅샷 완전성을 읽는다.
	 *
	 * 격자 수치 자체는 PARTIAL 스냅샷도 포함해 계산하므로, 화면이 "이 기수는 N건이 모수에서
	 * 빠졌다"를 함께 표시할 수 있도록 판단 재료만 올린다. 값은 DB에 이미 있었고 응답에만
	 * 실리지 않던 것이라 집계 결과는 이 변경으로 달라지지 않는다.
	 */
	@Override
	public List<SnapshotCompletionRow> findSnapshotCompletion(UUID organizationId, List<UUID> cohortIds) {
		String sql = (ACTIVE_SNAPSHOT_CTE + """
				SELECT cohort_id, completion_status, sample_count, missing_count
				FROM active_snapshot
				""").formatted(placeholders(cohortIds.size()));

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new SnapshotCompletionRow(
						rs.getObject("cohort_id", UUID.class),
						rs.getString("completion_status"),
						rs.getLong("sample_count"),
						rs.getLong("missing_count")
				),
				snapshotParameters(organizationId, cohortIds)
		);
	}

	/**
	 * 개념별 평균 도달 단계의 분자·분모를 구한다.
	 *
	 * CONCEPT_REACHED_LEVEL grain은 도달 단계(L0~L4)마다 한 행씩 있고 그 행의 인원이
	 * numerator에 담기므로 Σ(단계 × 인원)과 Σ인원을 모아 평균의 재료를 만든다.
	 * 참여자 수는 정의서 View와 같은 COALESCE(numerator, metric_value, 0) 규칙을 따른다.
	 *
	 * 개념 키를 teaches_id로 바로 읽지 않고 project_verification_concept를 거쳐 COALESCE 하는 이유는
	 * report_metric의 CHECK 제약이 두 개념 키 중 정확히 하나만 채우도록 강제하기 때문이다.
	 * teaches_id만 보면 반대쪽으로 저장된 행이 조용히 빠져 개념이 통째로 사라진다.
	 *
	 * 집계 상태는 나쁜 쪽이 이기도록 우선순위를 매긴다. 단순 MAX를 쓰면 사전순으로
	 * SINGLE_SOURCE가 이겨 POLICY_REQUIRED가 가려지고, 산식이 확정되지 않은 개념의
	 * 평균을 그대로 비교하게 된다.
	 */
	@Override
	public List<ConceptLevelRow> aggregateConceptLevels(UUID organizationId, List<UUID> cohortIds) {
		String sql = (ACTIVE_SNAPSHOT_CTE + """
				,
				concept_level AS (
					SELECT
						s.cohort_id,
						COALESCE(rm.teaches_id, pvc.teaches_id) AS teaches_id,
						SUM(rm.reached_level * COALESCE(rm.numerator, rm.metric_value, 0)) AS level_sum,
						SUM(COALESCE(rm.numerator, rm.metric_value, 0)) AS participant_count,
						COALESCE(MAX(rm.missing_count), 0) AS missing_count,
						CASE
							WHEN BOOL_OR(rm.aggregation_status = 'FAILED') THEN 'FAILED'
							WHEN BOOL_OR(rm.aggregation_status = 'POLICY_REQUIRED') THEN 'POLICY_REQUIRED'
							WHEN BOOL_OR(rm.aggregation_status = 'MULTIPLE_SOURCE_ROUNDS') THEN 'MULTIPLE_SOURCE_ROUNDS'
							WHEN BOOL_OR(rm.aggregation_status = 'CALCULATED') THEN 'CALCULATED'
							ELSE 'SINGLE_SOURCE'
						END AS aggregation_status,
						(ARRAY_AGG(rm.curriculum_version_id ORDER BY rm.display_order NULLS LAST, rm.curriculum_version_id))[1]
							AS curriculum_version_id
					FROM report_metric rm
					JOIN active_snapshot s ON s.snapshot_id = rm.snapshot_id
					LEFT JOIN project_verification_concept pvc
						ON pvc.project_concept_id = rm.project_verification_concept_id
					WHERE rm.section_code = 'CONCEPT_REACH_DISTRIBUTION'
						AND rm.metric_grain_code = 'CONCEPT_REACHED_LEVEL'
						AND COALESCE(rm.teaches_id, pvc.teaches_id) IS NOT NULL
						AND rm.reached_level IS NOT NULL
					GROUP BY s.cohort_id, COALESCE(rm.teaches_id, pvc.teaches_id)
				)
				SELECT DISTINCT ON (cl.cohort_id, cl.teaches_id)
					cl.cohort_id,
					cl.teaches_id,
					t.canonical_name,
					t.status AS teaches_status,
					t.merged_into_teaches_id,
					cl.level_sum,
					cl.participant_count,
					cl.missing_count,
					cl.aggregation_status,
					cl.curriculum_version_id,
					cv.version_no,
					cm.title AS curriculum_title,
					cs.sequence_no AS section_sequence_no,
					cs.title AS section_title,
					ctm.page_start,
					ctm.page_end
				FROM concept_level cl
				JOIN teaches t ON t.teaches_id = cl.teaches_id
				LEFT JOIN curriculum_version cv ON cv.version_id = cl.curriculum_version_id
				LEFT JOIN curriculum_material cm
					ON cm.material_id = cv.material_id AND cm.deleted_at IS NULL
				LEFT JOIN curriculum_teaches_mapping ctm
					ON ctm.teaches_id = cl.teaches_id
					AND ctm.version_id = cl.curriculum_version_id
					AND ctm.mapping_status = 'ACTIVE'
				LEFT JOIN curriculum_section cs ON cs.section_id = ctm.section_id
				ORDER BY cl.cohort_id, cl.teaches_id, ctm.sequence_no NULLS LAST, ctm.mapping_id
				""").formatted(placeholders(cohortIds.size()));

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new ConceptLevelRow(
						rs.getObject("cohort_id", UUID.class),
						rs.getObject("teaches_id", UUID.class),
						rs.getString("canonical_name"),
						rs.getString("teaches_status"),
						rs.getObject("merged_into_teaches_id", UUID.class),
						rs.getBigDecimal("level_sum"),
						rs.getLong("participant_count"),
						rs.getLong("missing_count"),
						rs.getString("aggregation_status"),
						rs.getObject("curriculum_version_id", UUID.class),
						rs.getObject("version_no", Integer.class),
						rs.getString("curriculum_title"),
						rs.getObject("section_sequence_no", Integer.class),
						rs.getString("section_title"),
						rs.getObject("page_start", Integer.class),
						rs.getObject("page_end", Integer.class)
				),
				snapshotParameters(organizationId, cohortIds)
		);
	}

	/**
	 * 개념이 검증된 회차를 찾는다.
	 *
	 * 평균을 읽는 CONCEPT_REACHED_LEVEL grain에는 회차 축이 없어 '미프 3차' 라벨을 여기서 따로 가져온다.
	 * 이 섹션은 개념 키가 주로 project_verification_concept_id라 teaches_id로 바꾸려면 한 단계 더 조인하며,
	 * CHECK 제약상 teaches_id 쪽으로 저장될 수도 있어 COALESCE로 두 경우를 모두 받는다.
	 * 한 개념이 여러 회차에서 검증됐으면 화면이 행마다 라벨 하나만 쓰므로 가장 최근 회차를 남긴다.
	 */
	@Override
	public List<ConceptRoundRow> findConceptRounds(UUID organizationId, List<UUID> cohortIds) {
		String sql = (ACTIVE_SNAPSHOT_CTE + """
				SELECT DISTINCT ON (s.cohort_id, COALESCE(rm.teaches_id, pvc.teaches_id))
					s.cohort_id,
					COALESCE(rm.teaches_id, pvc.teaches_id) AS teaches_id,
					r.round_no,
					r.round_name
				FROM report_metric rm
				JOIN active_snapshot s ON s.snapshot_id = rm.snapshot_id
				LEFT JOIN project_verification_concept pvc
					ON pvc.project_concept_id = rm.project_verification_concept_id
				JOIN project_assessment_round r
					ON r.assessment_round_id = rm.source_assessment_round_id
					AND r.deleted_at IS NULL
				WHERE rm.section_code = 'ROUND_CONCEPT_RESULT'
					AND COALESCE(rm.teaches_id, pvc.teaches_id) IS NOT NULL
				ORDER BY s.cohort_id, COALESCE(rm.teaches_id, pvc.teaches_id), r.round_no DESC
				""").formatted(placeholders(cohortIds.size()));

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new ConceptRoundRow(
						rs.getObject("cohort_id", UUID.class),
						rs.getObject("teaches_id", UUID.class),
						rs.getObject("round_no", Integer.class),
						rs.getString("round_name")
				),
				snapshotParameters(organizationId, cohortIds)
		);
	}

	private Object[] snapshotParameters(UUID organizationId, List<UUID> cohortIds) {
		List<Object> parameters = new ArrayList<>();
		parameters.add(organizationId);
		parameters.addAll(cohortIds);
		return parameters.toArray();
	}

	private static String placeholders(int count) {
		return String.join(", ", Collections.nCopies(count, "?"));
	}
}
