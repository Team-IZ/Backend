package com.bigproject.backend.domain.analytics.infrastructure;

import com.bigproject.backend.domain.analytics.domain.RiskTraineeQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcRiskTraineeQueryRepository implements RiskTraineeQueryRepository {

	// 위험 유형. INTV.INTERVIEW_CANDIDATE_REASON_REASON_CODE의 5개 값 중 단계 하락·지속 저점만 사용한다.
	// INVALID_ATTEMPT는 목록에서 뺀다. 무효 응시(CONFIRMED_INVALID)를 미집계로 분모에서 제외하므로
	// 그 교육생은 분모에 없고, 분자에만 남기면 분자가 분모를 벗어난다.
	// CONTRIBUTION_UNDERSTANDING_GAP·LOW_PARTICIPATION은 이 화면의 집계 대상이 아니다.
	private static final String RISK_REASON_CODES = "'STAGE_DECLINE', 'PERSISTENT_LOW'";

	// 집계 대상(분모) 조건. 미집계 3종(미응시·중단·무효 응시)을 모두 제외한다.
	// 분자도 이 조건을 함께 걸어 분자가 항상 분모의 부분집합이 되도록 한다.
	private static final String ELIGIBLE_CONDITION =
			"a.terminal_reason_code IS DISTINCT FROM 'NOT_ATTENDED' "
					+ "AND a.terminal_reason_code IS DISTINCT FROM 'SESSION_INCOMPLETE' "
					+ "AND a.validity_review_status <> 'CONFIRMED_INVALID'";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<CohortScope> findCohortScope(UUID cohortId) {
		return jdbcTemplate.query(
				"SELECT cohort_id, org_id FROM cohort WHERE cohort_id = ? AND deleted_at IS NULL",
				(rs, rowNum) -> new CohortScope(
						rs.getObject("cohort_id", UUID.class),
						rs.getObject("org_id", UUID.class)
				),
				cohortId
		).stream().findFirst();
	}

	@Override
	public boolean classroomBelongsToCohort(UUID classroomId, UUID cohortId, UUID organizationId) {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM "class"
				WHERE class_id = ?
					AND cohort_id = ?
					AND org_id = ?
					AND deleted_at IS NULL
				""",
				Integer.class,
				classroomId,
				cohortId,
				organizationId
		);
		return count != null && count > 0;
	}

	@Override
	public List<RoundRow> findRounds(RoundCriteria criteria) {
		return jdbcTemplate.query(
				"""
				SELECT
					r.assessment_round_id,
					r.round_no,
					r.round_name,
					p.project_id,
					p.name AS project_name,
					r.status AS round_status
				FROM project_assessment_round r
				JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
				WHERE r.cohort_id = ?
					AND r.org_id = ?
					AND r.deleted_at IS NULL
					AND p.project_category = ?
					AND r.round_no BETWEEN ? AND ?
				ORDER BY p.sequence_no, r.round_no, r.assessment_round_id
				""",
				(rs, rowNum) -> new RoundRow(
						rs.getObject("assessment_round_id", UUID.class),
						rs.getInt("round_no"),
						rs.getString("round_name"),
						rs.getObject("project_id", UUID.class),
						rs.getString("project_name"),
						rs.getString("round_status")
				),
				criteria.cohortId(),
				criteria.organizationId(),
				criteria.projectCategory(),
				criteria.fromRoundNo(),
				criteria.toRoundNo()
		);
	}

	/**
	 * 회차 × 반 격자를 한 번의 질의로 집계한다.
	 *
	 * 분모(eligible)와 분자(risk)를 같은 attempt_scope에서 뽑아 반 귀속을 한 경로로 통일한다.
	 * interview_candidate.class_id를 그대로 쓰지 않는 이유는 분모 쪽 반 귀속과 어긋나면
	 * 반별 분자가 분모를 넘을 수 있기 때문이다.
	 */
	@Override
	public List<RiskCellRow> aggregateRiskCells(RoundCriteria criteria) {
		String sql = """
				WITH round_scope AS (
					SELECT
						r.assessment_round_id,
						COALESCE(r.submission_due_at, r.scheduled_at, r.updated_at) AS class_anchor_at
					FROM project_assessment_round r
					JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
					WHERE r.cohort_id = ?
						AND r.org_id = ?
						AND r.deleted_at IS NULL
						AND p.project_category = ?
						AND r.round_no BETWEEN ? AND ?
				),
				attempt_scope AS (
					SELECT
						ma.assessment_round_id,
						ma.user_id,
						round_class.class_id,
						ma.terminal_reason_code,
						ma.validity_review_status
					FROM measurement_attempt ma
					JOIN round_scope rs ON rs.assessment_round_id = ma.assessment_round_id
					LEFT JOIN LATERAL (
						SELECT cmb.class_id
						FROM cohort_member cm
						JOIN class_membership cmb ON cmb.cohort_member_id = cm.cohort_member_id
						JOIN "class" cl ON cl.class_id = cmb.class_id AND cl.deleted_at IS NULL
						WHERE cm.user_id = ma.user_id
							AND cm.cohort_id = ma.cohort_id
							AND cm.org_id = ma.org_id
							AND cmb.assigned_at <= rs.class_anchor_at
							AND (cmb.unassigned_at IS NULL OR cmb.unassigned_at > rs.class_anchor_at)
						ORDER BY cmb.assigned_at DESC, cmb.class_membership_id DESC
						LIMIT 1
					) round_class ON TRUE
					WHERE ma.attempt_type = 'INITIAL'
						AND ma.cohort_id = ?
						AND ma.org_id = ?
				),
				risk_scope AS (
					SELECT ic.assessment_round_id, ic.user_id
					FROM interview_candidate ic
					JOIN interview_candidate_reason icr ON icr.candidate_id = ic.candidate_id
					JOIN round_scope rs ON rs.assessment_round_id = ic.assessment_round_id
					WHERE ic.cohort_id = ?
						AND ic.org_id = ?
						AND icr.reason_code IN (%s)
						AND icr.evaluation_status = 'MATCHED'
						AND icr.reason_status = 'ACTIVE'
					GROUP BY ic.assessment_round_id, ic.user_id
				)
				SELECT
					a.assessment_round_id,
					a.class_id,
					COUNT(*) FILTER (WHERE %s) AS eligible_count,
					COUNT(*) FILTER (WHERE r.user_id IS NOT NULL AND %s) AS risk_count,
					COUNT(*) FILTER (WHERE a.terminal_reason_code = 'NOT_ATTENDED') AS not_attended_count,
					COUNT(*) FILTER (WHERE a.terminal_reason_code = 'SESSION_INCOMPLETE') AS session_incomplete_count,
					COUNT(*) FILTER (WHERE a.validity_review_status = 'CONFIRMED_INVALID') AS invalid_attempt_count
				FROM attempt_scope a
				LEFT JOIN risk_scope r
					ON r.assessment_round_id = a.assessment_round_id
					AND r.user_id = a.user_id
				GROUP BY a.assessment_round_id, a.class_id
				""".formatted(RISK_REASON_CODES, ELIGIBLE_CONDITION, ELIGIBLE_CONDITION);

		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new RiskCellRow(
						rs.getObject("assessment_round_id", UUID.class),
						rs.getObject("class_id", UUID.class),
						rs.getLong("eligible_count"),
						rs.getLong("risk_count"),
						rs.getLong("not_attended_count"),
						rs.getLong("session_incomplete_count"),
						rs.getLong("invalid_attempt_count")
				),
				criteria.cohortId(),
				criteria.organizationId(),
				criteria.projectCategory(),
				criteria.fromRoundNo(),
				criteria.toRoundNo(),
				criteria.cohortId(),
				criteria.organizationId(),
				criteria.cohortId(),
				criteria.organizationId()
		);
	}

	@Override
	public List<ClassRosterRow> findClassRosters(UUID cohortId, UUID organizationId) {
		// 반을 옮긴 교육생이 두 반에 중복 계상되지 않도록 최근 배정 한 건만 남긴다.
		return jdbcTemplate.query(
				"""
				WITH member_class AS (
					SELECT DISTINCT ON (cm.cohort_member_id)
						cm.cohort_member_id,
						cm.left_at,
						cmb.class_id
					FROM cohort_member cm
					JOIN app_user u ON u.user_id = cm.user_id
					JOIN "role" ro ON ro.role_id = u.role_id
					JOIN class_membership cmb ON cmb.cohort_member_id = cm.cohort_member_id
					WHERE cm.cohort_id = ?
						AND cm.org_id = ?
						AND ro.code = 'TRAINEE'
					ORDER BY cm.cohort_member_id, cmb.assigned_at DESC, cmb.class_membership_id DESC
				)
				SELECT
					cl.class_id,
					cl.name AS class_name,
					COUNT(mc.cohort_member_id) FILTER (WHERE mc.left_at IS NULL) AS trainee_count,
					COUNT(mc.cohort_member_id) FILTER (WHERE mc.left_at IS NOT NULL) AS withdrawn_count
				FROM "class" cl
				LEFT JOIN member_class mc ON mc.class_id = cl.class_id
				WHERE cl.cohort_id = ?
					AND cl.org_id = ?
					AND cl.deleted_at IS NULL
				GROUP BY cl.class_id, cl.name
				ORDER BY cl.name, cl.class_id
				""",
				(rs, rowNum) -> new ClassRosterRow(
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getLong("trainee_count"),
						rs.getLong("withdrawn_count")
				),
				cohortId,
				organizationId,
				cohortId,
				organizationId
		);
	}

	@Override
	public RosterCount findCohortRoster(UUID cohortId, UUID organizationId) {
		RosterCount roster = jdbcTemplate.query(
				"""
				SELECT
					COUNT(*) FILTER (WHERE cm.left_at IS NULL) AS trainee_count,
					COUNT(*) FILTER (WHERE cm.left_at IS NOT NULL) AS withdrawn_count
				FROM cohort_member cm
				JOIN app_user u ON u.user_id = cm.user_id
				JOIN "role" ro ON ro.role_id = u.role_id
				WHERE cm.cohort_id = ?
					AND cm.org_id = ?
					AND ro.code = 'TRAINEE'
				""",
				(rs, rowNum) -> new RosterCount(rs.getLong("trainee_count"), rs.getLong("withdrawn_count")),
				cohortId,
				organizationId
		).stream().findFirst().orElse(null);
		return roster == null ? new RosterCount(0, 0) : roster;
	}
}
