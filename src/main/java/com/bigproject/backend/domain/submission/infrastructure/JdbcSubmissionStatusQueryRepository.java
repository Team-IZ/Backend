package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.domain.SubmissionStatusQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 제출 현황 조회의 네이티브 SQL 구현.
 *
 * <p>반 필터는 <b>SQL 문자열을 이어 붙여</b> 만든다. {@code (CAST(? AS uuid) IS NULL OR ...)} 한 벌로
 * 처리하면 파라미터가 두 배로 늘고, PostgreSQL이 null 파라미터의 타입을 정하지 못해 캐스팅을 붙여야만
 * 하는 자리가 생긴다. 조건이 하나뿐이라 분기하는 편이 읽기도 쉽다.
 *
 * <p>담당 반 제한은 조인이 아니라 {@code EXISTS}로 건다. {@code manager_assignment}는 배정·해제 이력이
 * 쌓이는 표라 같은 (매니저, 반)에 행이 여럿일 수 있고, 조인하면 그만큼 팀 행이 복제돼 집계가 부풀려진다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcSubmissionStatusQueryRepository implements SubmissionStatusQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 담당 반 제한. {@code classColumn}은 호출부가 고정한 컬럼 이름이며 외부 입력이 아니다.
	 * 파라미터는 매니저 ID 하나이고, 호출부의 인자 순서가 이 조각의 위치와 맞아야 한다.
	 */
	private static String managedClassFilter(String classColumn) {
		return """
				\tAND EXISTS (
						SELECT 1
						FROM manager_assignment ma
						WHERE ma.class_id = %s
							AND ma.manager_user_id = ?
							AND ma.status = 'ACTIVE'
							AND ma.unassigned_at IS NULL
					)
				""".formatted(classColumn);
	}

	@Override
	public boolean isClassManagedBy(UUID managerUserId, UUID classId, UUID cohortId) {
		Boolean managed = jdbcTemplate.queryForObject(
				"""
				SELECT EXISTS (
					SELECT 1
					FROM manager_assignment ma
					JOIN "class" c ON c.class_id = ma.class_id AND c.deleted_at IS NULL
					WHERE ma.manager_user_id = ?
						AND ma.class_id = ?
						AND c.cohort_id = ?
						AND ma.status = 'ACTIVE'
						AND ma.unassigned_at IS NULL
				)
				""",
				Boolean.class,
				managerUserId,
				classId,
				cohortId
		);
		return Boolean.TRUE.equals(managed);
	}

	@Override
	public Optional<RoundScope> findRound(UUID projectId, int roundNo) {
		return jdbcTemplate.query(
				"""
				SELECT
					r.assessment_round_id,
					p.project_id,
					p.org_id,
					p.cohort_id,
					p.name AS project_name,
					r.round_no,
					r.round_name,
					r.submission_due_at,
					p.lifecycle_status
				FROM project_assessment_round r
				JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
				WHERE r.project_id = ?
					AND r.round_no = ?
					AND r.deleted_at IS NULL
				""",
				(rs, rowNum) -> new RoundScope(
						rs.getObject("assessment_round_id", UUID.class),
						rs.getObject("project_id", UUID.class),
						rs.getObject("org_id", UUID.class),
						rs.getObject("cohort_id", UUID.class),
						rs.getString("project_name"),
						rs.getInt("round_no"),
						rs.getString("round_name"),
						instant(rs, "submission_due_at"),
						rs.getString("lifecycle_status")
				),
				projectId,
				roundNo
		).stream().findFirst();
	}

	/**
	 * 제출·분석을 LATERAL로 팀당 한 건씩 접어 넣는다. 그냥 조인하면 재제출이 있는 팀의 행이 불어나
	 * 팀 수가 부풀려진다. 분석은 <b>그 제출에 매인</b> 것만 본다 — 이전 제출의 분석이 남아 있어도
	 * 새 제출의 상태로 오인되지 않게 {@code submission_id}로 묶는다.
	 */
	@Override
	public List<TeamRow> findTeams(
			UUID projectId, UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId) {
		StringBuilder sql = new StringBuilder("""
				SELECT
					t.team_id,
					t.class_id,
					c.name AS class_name,
					t.team_number,
					t.name AS team_name,
					t.status AS team_status,
					sub.submission_id,
					sub.submitted_at,
					sub.method AS submission_method,
					sub.status AS submission_status,
					sub.submitted_by,
					submitter.name AS submitted_by_name,
					repo.repo_url,
					aj.job_id AS analysis_job_id,
					aj.status AS analysis_status,
					aj.failure_code,
					aj.failure_reason
				FROM team t
				JOIN "class" c ON c.class_id = t.class_id AND c.deleted_at IS NULL
				LEFT JOIN LATERAL (
					SELECT x.* FROM submission x
					WHERE x.team_id = t.team_id AND x.assessment_round_id = ?
					ORDER BY x.is_current DESC, x.submitted_at DESC LIMIT 1
				) sub ON TRUE
				LEFT JOIN repository repo ON repo.repository_id = sub.repository_id
				LEFT JOIN app_user submitter ON submitter.user_id = sub.submitted_by
				LEFT JOIN LATERAL (
					SELECT x.* FROM analysis_job x
					WHERE x.team_id = t.team_id
						AND x.assessment_round_id = ?
						AND x.submission_id = sub.submission_id
					ORDER BY x.execution_no DESC, x.started_at DESC NULLS LAST, x.job_id DESC LIMIT 1
				) aj ON TRUE
				WHERE t.project_id = ?
					AND t.org_id = ?
					AND t.deleted_at IS NULL
				""");
		List<Object> args = new ArrayList<>(List.of(assessmentRoundId, assessmentRoundId, projectId, organizationId));
		sql.append(managedClassFilter("t.class_id"));
		args.add(managerUserId);
		if (classId != null) {
			sql.append("\tAND t.class_id = ?\n");
			args.add(classId);
		}
		// 팀 번호가 TEXT라 그냥 정렬하면 10팀부터 1, 10, 11, 2 순이 된다. 숫자 부분을 먼저 본다.
		sql.append("ORDER BY c.name, NULLIF(REGEXP_REPLACE(t.team_number, '\\D', '', 'g'), '')::int "
				+ "NULLS LAST, t.team_number, t.team_id");

		return jdbcTemplate.query(
				sql.toString(),
				(rs, rowNum) -> new TeamRow(
						rs.getObject("team_id", UUID.class),
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getString("team_number"),
						rs.getString("team_name"),
						rs.getString("team_status"),
						rs.getObject("submission_id", UUID.class),
						instant(rs, "submitted_at"),
						rs.getString("repo_url"),
						rs.getObject("submitted_by", UUID.class),
						rs.getString("submitted_by_name"),
						rs.getString("submission_method"),
						rs.getString("submission_status"),
						rs.getObject("analysis_job_id", UUID.class),
						rs.getString("analysis_status"),
						rs.getString("failure_code"),
						rs.getString("failure_reason")
				),
				args.toArray()
		);
	}

	@Override
	public List<MemberRow> findMembers(
			UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId) {
		StringBuilder sql = new StringBuilder("""
				SELECT
					a.team_id,
					a.user_id,
					u.name AS user_name,
					a.primary_attempt_id,
					a.primary_attempt_status,
					a.completion_status,
					a.primary_assessment_open_at,
					a.primary_assessment_close_at,
					ma.terminal_at AS completed_at
				FROM assessment_round_attendance a
				JOIN app_user u ON u.user_id = a.user_id
				LEFT JOIN measurement_attempt ma ON ma.attempt_id = a.primary_attempt_id
				WHERE a.assessment_round_id = ?
					AND a.org_id = ?
				""");
		List<Object> args = new ArrayList<>(List.of(assessmentRoundId, organizationId));
		sql.append(managedClassFilter("a.class_id"));
		args.add(managerUserId);
		if (classId != null) {
			sql.append("\tAND a.class_id = ?\n");
			args.add(classId);
		}
		sql.append("ORDER BY u.name, a.user_id");

		return jdbcTemplate.query(
				sql.toString(),
				(rs, rowNum) -> new MemberRow(
						rs.getObject("team_id", UUID.class),
						rs.getObject("user_id", UUID.class),
						rs.getString("user_name"),
						rs.getObject("primary_attempt_id", UUID.class),
						rs.getString("primary_attempt_status"),
						rs.getString("completion_status"),
						instant(rs, "primary_assessment_open_at"),
						instant(rs, "primary_assessment_close_at"),
						instant(rs, "completed_at")
				),
				args.toArray()
		);
	}

	/**
	 * {@code active}만 본다. 요구사항은 버전이 올라가면 같은 {@code requirement_key}로 행이 하나 더
	 * 생기는데, 화면이 보여줘야 하는 것은 이번 회차에 실제로 적용 중인 한 벌이다.
	 */
	@Override
	public List<RequirementRow> findRequirements(UUID projectId, UUID organizationId) {
		return jdbcTemplate.query(
				"""
				SELECT
					r.requirement_id,
					r.requirement_key,
					r.sequence_no,
					r.title,
					r.description
				FROM project_requirement r
				WHERE r.project_id = ?
					AND r.org_id = ?
					AND r.active
				ORDER BY r.sequence_no, r.requirement_id
				""",
				(rs, rowNum) -> new RequirementRow(
						rs.getObject("requirement_id", UUID.class),
						rs.getString("requirement_key"),
						rs.getInt("sequence_no"),
						rs.getString("title"),
						rs.getString("description")
				),
				projectId,
				organizationId
		);
	}

	/**
	 * {@code DISTINCT ON}으로 (팀, 요구사항)별 최신 판정 한 건만 남긴다. 재분석하면 같은 조합의 행이
	 * {@code assessment_version}만 올려 쌓이므로, 최댓값을 고르지 않으면 한 요구사항이 표에 여러 번 나온다.
	 */
	@Override
	public List<RequirementResultRow> findRequirementResults(
			UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId) {
		StringBuilder sql = new StringBuilder("""
				SELECT DISTINCT ON (pra.team_id, pra.requirement_id)
					pra.team_id,
					pra.requirement_id,
					rq.requirement_key,
					rq.title,
					rq.sequence_no,
					pra.result,
					pra.evidence_summary,
					pra.assessed_by IS NULL AS judged_by_ai
				FROM project_requirement_assessment pra
				JOIN project_requirement rq ON rq.requirement_id = pra.requirement_id
				JOIN team t ON t.team_id = pra.team_id AND t.deleted_at IS NULL
				WHERE pra.assessment_round_id = ?
					AND pra.org_id = ?
				""");
		List<Object> args = new ArrayList<>(List.of(assessmentRoundId, organizationId));
		sql.append(managedClassFilter("t.class_id"));
		args.add(managerUserId);
		if (classId != null) {
			sql.append("\tAND t.class_id = ?\n");
			args.add(classId);
		}
		sql.append("ORDER BY pra.team_id, pra.requirement_id, pra.assessment_version DESC, "
				+ "pra.assessed_at DESC NULLS LAST");

		return jdbcTemplate.query(
				sql.toString(),
				(rs, rowNum) -> new RequirementResultRow(
						rs.getObject("team_id", UUID.class),
						rs.getObject("requirement_id", UUID.class),
						rs.getString("requirement_key"),
						rs.getString("title"),
						rs.getInt("sequence_no"),
						rs.getString("result"),
						rs.getString("evidence_summary"),
						rs.getBoolean("judged_by_ai")
				),
				args.toArray()
		);
	}

	@Override
	public long countUnassignedMembers(
			UUID projectId, UUID organizationId, UUID managerUserId, UUID classId) {
		StringBuilder sql = new StringBuilder("""
				SELECT COUNT(*)
				FROM project_membership pm
				WHERE pm.project_id = ?
					AND pm.org_id = ?
					AND pm.status = 'ACTIVE'
					AND NOT EXISTS (
						SELECT 1
						FROM team_membership tm
						JOIN team t ON t.team_id = tm.team_id AND t.deleted_at IS NULL
						WHERE tm.project_membership_id = pm.project_membership_id
							AND tm.to_at IS NULL
					)
				""");
		List<Object> args = new ArrayList<>(List.of(projectId, organizationId));
		sql.append(managedClassFilter("pm.class_id"));
		args.add(managerUserId);
		if (classId != null) {
			sql.append("\tAND pm.class_id = ?\n");
			args.add(classId);
		}

		Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
		return count == null ? 0L : count;
	}

	/** TIMESTAMPTZ → Instant. null 컬럼을 0 epoch로 만들지 않으려면 getTimestamp를 거쳐야 한다. */
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
