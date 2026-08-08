package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.CohortReportQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * OP-05 조회 SQL. v07 리포트 뷰를 읽는다.
 *
 * <p>집계 판정(위험자 수·저성과 여부·도달 분포)은 전부 {@code report_metric}에 이미 적재된
 * 값이고 뷰가 그것을 꺼내 준다. 여기서 다시 계산하지 않는다 — 발행 시점에 얼린 값이라
 * 지금 세면 스냅샷이 아니게 된다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcCohortReportQueryRepository implements CohortReportQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<CohortReportHeader> findHeader(UUID cohortId) {
		// 리포트 두 벌(수업 진단·기수 결산)의 상태를 한 행으로 붙인다.
		// 회차 수는 두 리포트가 같은 기수를 보므로 진단 쪽 값을 쓰고, 진단이 없으면 결산 쪽을 쓴다.
		String sql = """
				SELECT c.name  AS cohort_name,
				       c.start_date,
				       c.end_date,
				       diag.report_id          AS diagnosis_report_id,
				       diag.active_snapshot_id AS diagnosis_snapshot_id,
				       diag.published_at       AS diagnosis_published_at,
				       outc.report_id          AS outcome_report_id,
				       outc.active_snapshot_id AS outcome_snapshot_id,
				       outc.published_at       AS outcome_published_at,
				       COALESCE(diag.required_source_round_count,
				                outc.required_source_round_count, 0)  AS required_round_count,
				       COALESCE(diag.completed_source_round_count,
				                outc.completed_source_round_count, 0) AS completed_round_count
				FROM cohort c
				LEFT JOIN operator_cohort_report_status_view diag
				       ON diag.cohort_id = c.cohort_id
				      AND diag.report_type = 'COHORT_CURRICULUM_DIAGNOSIS'
				LEFT JOIN operator_cohort_report_status_view outc
				       ON outc.cohort_id = c.cohort_id
				      AND outc.report_type = 'COHORT_OUTCOME'
				WHERE c.cohort_id = ?
				  AND c.deleted_at IS NULL
				""";

		List<CohortReportHeader> rows = jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new CohortReportHeader(
				cohortId,
				rs.getString("cohort_name"),
				localDate(rs, "start_date"),
				localDate(rs, "end_date"),
				rs.getObject("diagnosis_report_id", UUID.class),
				rs.getObject("diagnosis_snapshot_id", UUID.class),
				instant(rs, "diagnosis_published_at"),
				rs.getObject("outcome_report_id", UUID.class),
				rs.getObject("outcome_snapshot_id", UUID.class),
				instant(rs, "outcome_published_at"),
				rs.getInt("required_round_count"),
				rs.getInt("completed_round_count")
		), cohortId);

		return rows.stream().findFirst();
	}

	@Override
	public CohortScale findScale(UUID cohortId) {
		// 스냅샷이 아니라 현재 값이다. 화면 머리의 "교육생 N명 · 반 M개"는 문서 표지 정보라
		// 발행 시점 고정이 아니어도 읽는 데 지장이 없다.
		String sql = """
				SELECT (SELECT COUNT(*) FROM cohort_member cm
				         WHERE cm.cohort_id = ? AND cm.left_at IS NULL)  AS trainee_count,
				       (SELECT COUNT(*) FROM class cl
				         WHERE cl.cohort_id = ? AND cl.deleted_at IS NULL) AS class_count
				""";

		return jdbcTemplate.queryForObject(sql, (ResultSet rs, int rowNum) ->
				new CohortScale(rs.getInt("trainee_count"), rs.getInt("class_count")), cohortId, cohortId);
	}

	@Override
	public List<CurriculumSummaryRow> findCurriculumSummaries(UUID diagnosisSnapshotId) {
		String sql = """
				SELECT DISTINCT
				       s.curriculum_version_id,
				       s.curriculum_name_snapshot,
				       s.eligible_count,
				       s.assessed_count,
				       s.missing_count
				FROM operator_curriculum_diagnosis_summary_view s
				WHERE s.snapshot_id = ?
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new CurriculumSummaryRow(
				rs.getObject("curriculum_version_id", UUID.class),
				rs.getString("curriculum_name_snapshot"),
				rs.getInt("eligible_count"),
				rs.getInt("assessed_count"),
				rs.getInt("missing_count")
		), diagnosisSnapshotId);
	}

	@Override
	public ExcludedRow findExcluded(UUID diagnosisSnapshotId) {
		/*
		 * `->>`로 텍스트를 꺼내 자바에서 파싱한다. SQL에서 `::int`로 캐스팅하면 페이로드에
		 * 숫자가 아닌 값이 하나만 들어 있어도 쿼리 전체가 에러로 죽는데, 그러면 제외 인원
		 * 하나 때문에 리포트 화면이 통째로 안 열린다. 키가 없으면 `->>`는 NULL을 준다.
		 */
		String sql = """
				SELECT s.summary_payload -> 'excluded' ->> 'notTaken'    AS not_taken,
				       s.summary_payload -> 'excluded' ->> 'invalid'     AS invalid,
				       s.summary_payload -> 'excluded' ->> 'interrupted' AS interrupted
				FROM report_snapshot s
				WHERE s.snapshot_id = ?
				""";

		List<ExcludedRow> rows = jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new ExcludedRow(
				parseCount(rs.getString("not_taken")),
				parseCount(rs.getString("invalid")),
				parseCount(rs.getString("interrupted"))
		), diagnosisSnapshotId);

		return rows.stream().findFirst().orElseGet(ExcludedRow::zero);
	}

	@Override
	public List<ConceptDistributionRow> findConceptDistribution(UUID diagnosisSnapshotId) {
		/*
		 * 개념 × 도달 단계(0~4)가 행으로 나온다. 화면의 `section` 문자열
		 * (`네트워킹 (p.28–40)`)을 만들려면 교안 섹션까지 가야 하는데, 개념(teaches)과
		 * 섹션을 잇는 것이 curriculum_teaches_mapping이다.
		 *
		 * mapping_status='ACTIVE' 필터는 curriculum_section_detail_view가 쓰는 조건과 같다.
		 * 매핑이 여러 건일 수 있어 DISTINCT ON으로 sequence_no가 가장 앞선 것 하나만 쓴다 —
		 * 같은 개념이 표에 두 번 나오면 분포 합이 인원 수를 넘는다.
		 */
		String sql = """
				SELECT d.teaches_id,
				       d.concept_name_snapshot,
				       d.curriculum_version_id,
				       cm.title       AS curriculum_name,
				       cv.version_no,
				       cs.title       AS section_title,
				       cs.page_start  AS section_page_start,
				       cs.page_end    AS section_page_end,
				       d.reached_level,
				       d.participant_count,
				       d.denominator
				FROM operator_curriculum_concept_distribution_view d
				LEFT JOIN curriculum_version cv  ON cv.version_id  = d.curriculum_version_id
				LEFT JOIN curriculum_material cm ON cm.material_id = cv.material_id
				LEFT JOIN LATERAL (
				       SELECT m.section_id
				       FROM curriculum_teaches_mapping m
				       WHERE m.teaches_id = d.teaches_id
				         AND m.version_id = d.curriculum_version_id
				         AND m.mapping_status = 'ACTIVE'
				       ORDER BY m.sequence_no NULLS LAST
				       LIMIT 1
				) map ON TRUE
				LEFT JOIN curriculum_section cs ON cs.section_id = map.section_id
				WHERE d.snapshot_id = ?
				ORDER BY cm.title, cs.sequence_no NULLS LAST, d.concept_name_snapshot, d.reached_level
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new ConceptDistributionRow(
				rs.getObject("teaches_id", UUID.class),
				rs.getString("concept_name_snapshot"),
				rs.getObject("curriculum_version_id", UUID.class),
				rs.getString("curriculum_name"),
				integer(rs, "version_no"),
				rs.getString("section_title"),
				integer(rs, "section_page_start"),
				integer(rs, "section_page_end"),
				rs.getInt("reached_level"),
				rs.getInt("participant_count"),
				integer(rs, "denominator")
		), diagnosisSnapshotId);
	}

	@Override
	public List<RoundConceptRow> findRoundConcepts(UUID diagnosisSnapshotId) {
		String sql = """
				SELECT r.round_id,
				       r.round_name,
				       r.concept_id,
				       r.concept_name_snapshot,
				       r.reached_level,
				       r.participant_count,
				       r.denominator,
				       r.missing_count
				FROM operator_curriculum_diagnosis_round_view r
				WHERE r.snapshot_id = ?
				ORDER BY r.round_name, r.concept_name_snapshot, r.reached_level
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new RoundConceptRow(
				rs.getObject("round_id", UUID.class),
				rs.getString("round_name"),
				rs.getObject("concept_id", UUID.class),
				rs.getString("concept_name_snapshot"),
				integer(rs, "reached_level"),
				rs.getInt("participant_count"),
				integer(rs, "denominator"),
				integer(rs, "missing_count")
		), diagnosisSnapshotId);
	}

	@Override
	public List<ClassRiskRow> findClassRisks(UUID outcomeSnapshotId) {
		String sql = """
				SELECT k.class_id,
				       k.class_name_snapshot,
				       k.eligible_trainee_count,
				       k.risk_trainee_count
				FROM operator_cohort_class_risk_view k
				WHERE k.snapshot_id = ?
				ORDER BY k.display_order NULLS LAST, k.class_name_snapshot
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new ClassRiskRow(
				rs.getObject("class_id", UUID.class),
				rs.getString("class_name_snapshot"),
				rs.getInt("eligible_trainee_count"),
				rs.getInt("risk_trainee_count")
		), outcomeSnapshotId);
	}

	@Override
	public List<GroupShortfallRow> findGroupShortfalls(UUID outcomeSnapshotId) {
		// underperformance_flag가 참인 조합만 화면에 올린다 — 뷰는 평가된 조합 전부를 낸다.
		String sql = """
				SELECT g.class_id,
				       g.class_name_snapshot,
				       g.teaches_id,
				       g.concept_name_snapshot,
				       cm.title      AS curriculum_name,
				       cs.title      AS section_title,
				       cs.page_start AS section_page_start,
				       cs.page_end   AS section_page_end,
				       g.low_level_count,
				       g.denominator
				FROM operator_cohort_group_underperformance_view g
				LEFT JOIN LATERAL (
				       SELECT m.section_id, m.version_id
				       FROM curriculum_teaches_mapping m
				       WHERE m.teaches_id = g.teaches_id
				         AND m.mapping_status = 'ACTIVE'
				       ORDER BY m.sequence_no NULLS LAST
				       LIMIT 1
				) map ON TRUE
				LEFT JOIN curriculum_section cs  ON cs.section_id = map.section_id
				LEFT JOIN curriculum_version cv  ON cv.version_id = map.version_id
				LEFT JOIN curriculum_material cm ON cm.material_id = cv.material_id
				WHERE g.snapshot_id = ?
				  AND g.underperformance_flag IS TRUE
				ORDER BY g.class_name_snapshot, g.concept_name_snapshot
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new GroupShortfallRow(
				rs.getObject("class_id", UUID.class),
				rs.getString("class_name_snapshot"),
				rs.getObject("teaches_id", UUID.class),
				rs.getString("concept_name_snapshot"),
				rs.getString("curriculum_name"),
				rs.getString("section_title"),
				integer(rs, "section_page_start"),
				integer(rs, "section_page_end"),
				rs.getInt("low_level_count"),
				integer(rs, "denominator") == null ? 0 : integer(rs, "denominator")
		), outcomeSnapshotId);
	}

	@Override
	public List<TopStudentRow> findTopStudents(UUID outcomeSnapshotId) {
		// 제외된 참가자(eligibility_status가 대상 아님)는 우수 명단에 올리지 않는다.
		String sql = """
				SELECT p.user_id,
				       p.display_name_snapshot,
				       p.class_name_snapshot,
				       COALESCE(p.occurrence_count, 0) AS occurrence_count
				FROM operator_cohort_outcome_participant_view p
				WHERE p.snapshot_id = ?
				  AND p.participant_section_code = 'EXCELLENT_TRAINEE'
				  AND p.exclusion_reason_code IS NULL
				ORDER BY COALESCE(p.occurrence_count, 0) DESC, p.display_name_snapshot
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new TopStudentRow(
				rs.getObject("user_id", UUID.class),
				rs.getString("display_name_snapshot"),
				rs.getString("class_name_snapshot"),
				rs.getInt("occurrence_count")
		), outcomeSnapshotId);
	}

	@Override
	public List<TopStudentRoundRow> findTopStudentRounds(UUID outcomeSnapshotId) {
		String sql = """
				SELECT o.user_id,
				       r.round_no
				FROM operator_cohort_outcome_occurrence_view o
				JOIN project_assessment_round r ON r.assessment_round_id = o.round_id
				WHERE o.snapshot_id = ?
				ORDER BY o.user_id, r.round_no
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new TopStudentRoundRow(
				rs.getObject("user_id", UUID.class),
				rs.getInt("round_no")
		), outcomeSnapshotId);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
		Date date = rs.getDate(column);
		return date == null ? null : date.toLocalDate();
	}

	/**
	 * 페이로드의 제외 인원 하나. 키가 없거나(NULL) 숫자가 아니면 0이다.
	 *
	 * <p>여기서는 결측과 0을 구분하지 않는다 — 화면이 뺄셈 한 줄로 보여주는 값이라
	 * 표시할 자리가 없고, 파이프라인이 아직 이 키를 안 쓰는 옛 스냅샷도 열려야 한다.
	 * 값이 이상하면 조회를 깨뜨리는 대신 0으로 두고 로그로 알린다.
	 */
	private static int parseCount(String value) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			log.warn("summary_payload.excluded 값이 숫자가 아닙니다: {}", value);
			return 0;
		}
	}

	/** NULL 정수를 0으로 만들지 않기 위해 getObject를 거친다. 결측과 0은 다르다. */
	private static Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}
}
