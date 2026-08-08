package com.bigproject.backend.domain.analytics.infrastructure;

import com.bigproject.backend.domain.analytics.domain.GroupGapPolicy;
import com.bigproject.backend.domain.analytics.domain.OperationalActionQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcOperationalActionQueryRepository implements OperationalActionQueryRepository {

	private static final String MINI_PROJECT = "MINI_PROJECT";

	// 미니프로젝트 회차만 훑는 공통 범위. 빅프로젝트는 검증 개념 방식이 달라 같은 경보에 올리지 않는다.
	private static final String MINI_ROUND_SCOPE = """
			round_scope AS (
				SELECT
					r.assessment_round_id,
					r.round_no,
					r.round_name,
					p.project_id,
					p.name AS project_name,
					p.sequence_no AS project_sequence_no
				FROM project_assessment_round r
				JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
				WHERE p.cohort_id = ?
					AND p.org_id = ?
					AND p.project_category = '%s'
					AND r.deleted_at IS NULL
			)
			""".formatted(MINI_PROJECT);

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<UnassignedClassRow> findUnassignedClasses(UUID cohortId, UUID organizationId) {
		return jdbcTemplate.query(
				"""
				SELECT
					cl.class_id,
					cl.name AS class_name,
					(
						SELECT COUNT(*)
						FROM class_membership cmb
						JOIN cohort_member cm ON cm.cohort_member_id = cmb.cohort_member_id
						WHERE cmb.class_id = cl.class_id
							AND cmb.unassigned_at IS NULL
							AND cm.left_at IS NULL
					) AS trainee_count
				FROM "class" cl
				WHERE cl.cohort_id = ?
					AND cl.org_id = ?
					AND cl.deleted_at IS NULL
					AND cl.lifecycle_status <> 'CLOSED'
					AND NOT EXISTS (
						SELECT 1
						FROM manager_assignment ma
						WHERE ma.class_id = cl.class_id
							AND ma.status = 'ACTIVE'
							AND ma.unassigned_at IS NULL
					)
				ORDER BY cl.name, cl.class_id
				""",
				(rs, rowNum) -> new UnassignedClassRow(
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getLong("trainee_count")
				),
				cohortId,
				organizationId
		);
	}

	/**
	 * 분모는 이해도 검증 세션이 실제 발생한 팀이다.
	 * 분석만 돌고 아무도 응시하지 않은 팀을 '개념이 없던 팀'으로 세면 경보가 부풀려진다.
	 */
	@Override
	public List<ConceptGapRow> findConceptGaps(UUID cohortId, UUID organizationId) {
		return jdbcTemplate.query(
				"""
				WITH %s,
				participating_team AS (
					SELECT DISTINCT ca.assessment_round_id, ca.team_id
					FROM code_analysis ca
					JOIN round_scope rs ON rs.assessment_round_id = ca.assessment_round_id
					WHERE ca.status = 'ACTIVE'
						AND EXISTS (
							SELECT 1
							FROM measurement_attempt ma
							JOIN assessment_session s ON s.attempt_id = ma.attempt_id
							WHERE ma.code_analysis_id = ca.analysis_id
								AND ma.attempt_type = 'INITIAL'
						)
				),
				round_team_total AS (
					SELECT assessment_round_id, COUNT(*) AS participating_team_count
					FROM participating_team
					GROUP BY assessment_round_id
				),
				concept_gap AS (
					SELECT
						ca.assessment_round_id,
						pvc.teaches_id,
						COUNT(DISTINCT ca.team_id) FILTER (
							WHERE ap.generation_status = 'NOT_GENERATED'
								AND ap.not_generated_reason_code = 'NO_MATCHING_CODE_EVIDENCE'
						) AS gap_team_count
					FROM assessment_problem ap
					JOIN code_analysis ca ON ca.analysis_id = ap.code_analysis_id AND ca.status = 'ACTIVE'
					JOIN participating_team pt
						ON pt.assessment_round_id = ca.assessment_round_id AND pt.team_id = ca.team_id
					JOIN project_verification_concept pvc
						ON pvc.project_concept_id = ap.project_verification_concept_id
					WHERE ap.problem_scope = 'TEAM_SHARED_PROBLEM'
					GROUP BY ca.assessment_round_id, pvc.teaches_id
				)
				SELECT
					rs.assessment_round_id,
					rs.round_no,
					rs.round_name,
					rs.project_id,
					rs.project_name,
					cg.teaches_id,
					t.canonical_name AS concept_name,
					cg.gap_team_count,
					rt.participating_team_count
				FROM concept_gap cg
				JOIN round_scope rs ON rs.assessment_round_id = cg.assessment_round_id
				JOIN round_team_total rt ON rt.assessment_round_id = cg.assessment_round_id
				JOIN teaches t ON t.teaches_id = cg.teaches_id
				WHERE cg.gap_team_count > 0
				ORDER BY cg.gap_team_count DESC, rs.project_sequence_no DESC, rs.round_no DESC, t.canonical_name
				""".formatted(MINI_ROUND_SCOPE),
				(rs, rowNum) -> new ConceptGapRow(
						roundRef(rs),
						rs.getObject("teaches_id", UUID.class),
						rs.getString("concept_name"),
						rs.getLong("gap_team_count"),
						rs.getLong("participating_team_count")
				),
				cohortId,
				organizationId
		);
	}

	/**
	 * 도달 단계 산식은 heatmap View 3종이 공유하는 것과 같다.
	 * PASSED인 axis_code의 최댓값을 쓰고 통과 기록이 없으면 0단이다.
	 */
	@Override
	public List<GroupGapRow> findGroupGaps(UUID cohortId, UUID organizationId) {
		return jdbcTemplate.query(
				"""
				WITH %s,
				user_concept_level AS (
					SELECT
						ma.assessment_round_id,
						pm.class_id,
						ma.user_id,
						pvc.teaches_id,
						MAX(CASE WHEN ps.status = 'PASSED'
							THEN CASE ps.axis_code
								WHEN 'L4' THEN 4 WHEN 'L3' THEN 3 WHEN 'L2' THEN 2 WHEN 'L1' THEN 1 ELSE 0 END
							ELSE 0 END) AS reached_level
					FROM measurement_attempt ma
					JOIN round_scope rs ON rs.assessment_round_id = ma.assessment_round_id
					JOIN assessment_session s ON s.attempt_id = ma.attempt_id
					JOIN problem_stage ps ON ps.session_id = s.session_id
					JOIN assessment_problem ap
						ON ap.problem_id = ps.problem_id AND ap.problem_scope = 'TEAM_SHARED_PROBLEM'
					JOIN project_verification_concept pvc
						ON pvc.project_concept_id = ap.project_verification_concept_id
					JOIN project_membership pm
						ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id AND pm.status = 'ACTIVE'
					WHERE ma.attempt_type = 'INITIAL'
						AND ma.status = 'COMPLETED'
						AND ma.validity_review_status IS DISTINCT FROM 'CONFIRMED_INVALID'
					GROUP BY ma.assessment_round_id, pm.class_id, ma.user_id, pvc.teaches_id
				),
				class_size AS (
					SELECT rs.assessment_round_id, pm.class_id, COUNT(*) AS class_member_count
					FROM round_scope rs
					JOIN project_membership pm ON pm.project_id = rs.project_id AND pm.status = 'ACTIVE'
					GROUP BY rs.assessment_round_id, pm.class_id
				)
				SELECT
					rs.assessment_round_id,
					rs.round_no,
					rs.round_name,
					rs.project_id,
					rs.project_name,
					c.class_id,
					c.name AS class_name,
					t.teaches_id,
					t.canonical_name AS concept_name,
					COUNT(*) FILTER (WHERE ucl.reached_level <= ?) AS low_level_count,
					cs.class_member_count
				FROM user_concept_level ucl
				JOIN round_scope rs ON rs.assessment_round_id = ucl.assessment_round_id
				JOIN class_size cs
					ON cs.assessment_round_id = ucl.assessment_round_id AND cs.class_id = ucl.class_id
				JOIN "class" c ON c.class_id = ucl.class_id
				JOIN teaches t ON t.teaches_id = ucl.teaches_id
				GROUP BY
					rs.assessment_round_id, rs.round_no, rs.round_name, rs.project_id, rs.project_name,
					rs.project_sequence_no, c.class_id, c.name, t.teaches_id, t.canonical_name,
					cs.class_member_count
				ORDER BY
					(COUNT(*) FILTER (WHERE ucl.reached_level <= ?))::numeric
						/ NULLIF(cs.class_member_count, 0) DESC,
					rs.project_sequence_no DESC, rs.round_no DESC, c.name, t.canonical_name
				""".formatted(MINI_ROUND_SCOPE),
				(rs, rowNum) -> new GroupGapRow(
						roundRef(rs),
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getObject("teaches_id", UUID.class),
						rs.getString("concept_name"),
						rs.getLong("low_level_count"),
						rs.getLong("class_member_count")
				),
				cohortId,
				organizationId,
				GroupGapPolicy.LOW_LEVEL_MAX,
				com.bigproject.backend.domain.analytics.domain.GroupGapPolicy.LOW_LEVEL_MAX
		);
	}

	/**
	 * 지연 기산점은 면담 예정일이다. 예정일이 없거나 면담이 아직 생성되지 않은 후보는
	 * 지연일을 계산할 수 없으므로 별도 카운트로만 돌려주고 최대 지연일 계산에서 뺀다.
	 * 회차 범위는 미니프로젝트로 좁히지 않는다. 면담은 빅프로젝트 회차에서도 생긴다.
	 */
	@Override
	public List<InterviewBacklogRow> findInterviewBacklogs(UUID cohortId, UUID organizationId) {
		return jdbcTemplate.query(
				"""
				WITH round_scope AS (
					SELECT
						r.assessment_round_id,
						r.round_no,
						r.round_name,
						p.project_id,
						p.name AS project_name,
						p.sequence_no AS project_sequence_no
					FROM project_assessment_round r
					JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
					WHERE p.cohort_id = ?
						AND p.org_id = ?
						AND r.deleted_at IS NULL
				),
				unfinished AS (
					SELECT
						ic.class_id,
						ic.assessment_round_id,
						i.interview_id,
						i.status AS interview_status,
						i.planned_at,
						CASE WHEN i.status = 'PENDING'
								AND i.planned_at IS NOT NULL
								AND i.planned_at < CURRENT_TIMESTAMP
							THEN (EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - i.planned_at)) / 86400)::int
						END AS delay_days
					FROM interview_candidate ic
					JOIN round_scope rs ON rs.assessment_round_id = ic.assessment_round_id
					LEFT JOIN interview i ON i.candidate_id = ic.candidate_id
					WHERE ic.status IN ('ELIGIBLE', 'INTERVIEW_CREATED')
						AND (i.interview_id IS NULL OR i.status <> 'COMPLETED')
				)
				SELECT
					rs.assessment_round_id,
					rs.round_no,
					rs.round_name,
					rs.project_id,
					rs.project_name,
					c.class_id,
					c.name AS class_name,
					MAX(u.delay_days) AS max_delay_days,
					COUNT(*) AS pending_interview_count,
					COUNT(*) FILTER (WHERE u.interview_id IS NULL) AS not_created_count,
					COUNT(*) FILTER (WHERE u.interview_status = 'PENDING' AND u.planned_at IS NULL)
						AS unplanned_count
				FROM unfinished u
				JOIN "class" c ON c.class_id = u.class_id
				JOIN round_scope rs ON rs.assessment_round_id = u.assessment_round_id
				GROUP BY
					rs.assessment_round_id, rs.round_no, rs.round_name, rs.project_id, rs.project_name,
					rs.project_sequence_no, c.class_id, c.name
				HAVING MAX(u.delay_days) IS NOT NULL
				ORDER BY MAX(u.delay_days) DESC, rs.project_sequence_no DESC, rs.round_no DESC, c.name
				""",
				(rs, rowNum) -> new InterviewBacklogRow(
						roundRef(rs),
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getInt("max_delay_days"),
						rs.getLong("pending_interview_count"),
						rs.getLong("not_created_count"),
						rs.getLong("unplanned_count")
				),
				cohortId,
				organizationId
		);
	}

	private RoundRef roundRef(ResultSet rs) throws SQLException {
		return new RoundRef(
				rs.getObject("assessment_round_id", UUID.class),
				rs.getInt("round_no"),
				rs.getString("round_name"),
				rs.getObject("project_id", UUID.class),
				rs.getString("project_name")
		);
	}
}
