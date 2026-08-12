package com.bigproject.backend.domain.assessment.infrastructure;

import com.bigproject.backend.domain.assessment.domain.SubmissionMethodPolicy;
import com.bigproject.backend.domain.assessment.domain.TraineeHomeRound;
import com.bigproject.backend.domain.assessment.domain.TraineeHomeRoundRepository;
import com.bigproject.backend.domain.assessment.domain.TraineeMembership;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcTraineeHomeRoundRepository implements TraineeHomeRoundRepository {

	/*
	 * trainee_home_round_view 단일 조회 + cohort 조인 1개.
	 * View에는 cohort_id만 있고 기수 표시명이 없어 cohort.name만 따로 가져온다.
	 * 정렬은 서비스가 구획별로 다시 하므로 여기서는 안정적인 기본 순서만 준다.
	 */
	private static final String FIND_ROUNDS = """
			SELECT v.assessment_round_id,
			       v.round_no,
			       v.round_name,
			       v.round_status,
			       v.project_id,
			       v.project_name,
			       v.project_category,
			       v.curriculum_display_names,
			       v.cohort_id,
			       c.name AS cohort_name,
			       v.class_id_at_round,
			       v.class_name,
			       v.team_id_at_round,
			       v.team_number,
			       v.team_name,
			       v.representative_status,
			       v.default_action_code,
			       v.action_unavailable_reason_code,
			       v.warning_codes,
			       v.commit_email_status,
			       v.submission_method,
			       v.submission_status,
			       v.submitted_at,
			       v.can_submit,
			       v.can_resubmit,
			       v.analysis_phase,
			       v.analysis_job_status,
			       v.analysis_failure_code,
			       v.initial_attempt_status,
			       v.initial_session_status,
			       v.prepared_problem_count,
			       v.review_status,
			       v.completed_review_count,
			       v.report_id,
			       v.report_publish_status,
			       v.trainee_release_status,
			       v.explanation_status,
			       v.submission_due_at,
			       v.round_assessment_open_at,
			       v.round_assessment_due_at,
			       v.assessment_open_at,
			       v.assessment_close_at,
			       v.initial_terminal_at,
			       v.report_publish_mode,
			       v.report_publish_not_before_at,
			       v.manager_user_id,
			       v.manager_name,
			       v.as_of_at
			FROM trainee_home_round_view v
			LEFT JOIN cohort c ON c.cohort_id = v.cohort_id AND c.deleted_at IS NULL
			WHERE v.trainee_user_id = ?
			ORDER BY v.round_no DESC
			""";

	/*
	 * 회차 카드가 0행일 때만 쓴다. class_membership은 cohort_member에 붙는 기수 스코프라
	 * 진행 중인 프로젝트가 없어도 반까지는 표시할 수 있다.
	 * 반 배정이 없을 수 있으므로 class 쪽은 LEFT JOIN이다.
	 */
	private static final String FIND_MEMBERSHIP = """
			SELECT co.cohort_id,
			       co.name AS cohort_name,
			       cl.class_id,
			       cl.name AS class_name
			FROM cohort_member cm
			JOIN cohort co ON co.cohort_id = cm.cohort_id AND co.deleted_at IS NULL
			LEFT JOIN class_membership clm ON clm.cohort_member_id = cm.cohort_member_id
				AND clm.unassigned_at IS NULL
			LEFT JOIN class cl ON cl.class_id = clm.class_id AND cl.deleted_at IS NULL
			WHERE cm.user_id = ?
				AND cm.status = 'ACTIVE'
			ORDER BY cm.joined_at DESC
			LIMIT 1
			""";

	private static final String FIND_SUBMISSION_METHOD_POLICY = """
			SELECT p.allow_github_integration,
			       p.allow_zip_submission
			FROM app_user u
			JOIN organization_policy p ON p.org_id = u.org_id AND p.status = 'ACTIVE'
			WHERE u.user_id = ?
				AND u.deleted_at IS NULL
			ORDER BY p.policy_version DESC
			LIMIT 1
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<TraineeHomeRound> findAllByTraineeUserId(UUID traineeUserId) {
		return jdbcTemplate.query(FIND_ROUNDS, ROUND_MAPPER, traineeUserId);
	}

	@Override
	public Optional<TraineeMembership> findMembershipByUserId(UUID traineeUserId) {
		return jdbcTemplate.query(
				FIND_MEMBERSHIP,
				(rs, rowNum) -> new TraineeMembership(
						toUuid(rs, "cohort_id"),
						rs.getString("cohort_name"),
						toUuid(rs, "class_id"),
						rs.getString("class_name")
				),
				traineeUserId
		).stream().findFirst();
	}

	@Override
	public Optional<SubmissionMethodPolicy> findSubmissionMethodPolicyByUserId(UUID traineeUserId) {
		return jdbcTemplate.query(
				FIND_SUBMISSION_METHOD_POLICY,
				(rs, rowNum) -> new SubmissionMethodPolicy(
						rs.getBoolean("allow_github_integration"),
						rs.getBoolean("allow_zip_submission")
				),
				traineeUserId
		).stream().findFirst();
	}

	private static final RowMapper<TraineeHomeRound> ROUND_MAPPER = (rs, rowNum) -> new TraineeHomeRound(
			toUuid(rs, "assessment_round_id"),
			toInteger(rs, "round_no"),
			rs.getString("round_name"),
			rs.getString("round_status"),
			toUuid(rs, "project_id"),
			rs.getString("project_name"),
			rs.getString("project_category"),
			toStringList(rs.getArray("curriculum_display_names")),

			toUuid(rs, "cohort_id"),
			rs.getString("cohort_name"),
			toUuid(rs, "class_id_at_round"),
			rs.getString("class_name"),
			toUuid(rs, "team_id_at_round"),
			rs.getString("team_number"),
			rs.getString("team_name"),

			rs.getString("representative_status"),
			rs.getString("default_action_code"),
			rs.getString("action_unavailable_reason_code"),
			toStringList(rs.getArray("warning_codes")),

			rs.getString("commit_email_status"),
			rs.getString("submission_method"),
			rs.getString("submission_status"),
			toInstant(rs.getTimestamp("submitted_at")),
			rs.getBoolean("can_submit"),
			rs.getBoolean("can_resubmit"),

			rs.getString("analysis_phase"),
			rs.getString("analysis_job_status"),
			rs.getString("analysis_failure_code"),

			rs.getString("initial_attempt_status"),
			rs.getString("initial_session_status"),
			rs.getInt("prepared_problem_count"),
			rs.getString("review_status"),
			rs.getInt("completed_review_count"),

			toUuid(rs, "report_id"),
			rs.getString("report_publish_status"),
			rs.getString("trainee_release_status"),
			rs.getString("explanation_status"),

			toInstant(rs.getTimestamp("submission_due_at")),
			toInstant(rs.getTimestamp("round_assessment_open_at")),
			toInstant(rs.getTimestamp("round_assessment_due_at")),
			toInstant(rs.getTimestamp("assessment_open_at")),
			toInstant(rs.getTimestamp("assessment_close_at")),
			toInstant(rs.getTimestamp("initial_terminal_at")),
			rs.getString("report_publish_mode"),
			toInstant(rs.getTimestamp("report_publish_not_before_at")),

			toUuid(rs, "manager_user_id"),
			rs.getString("manager_name"),
			toInstant(rs.getTimestamp("as_of_at"))
	);

	private static UUID toUuid(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column);
		if (value == null) {
			return null;
		}
		return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
	}

	private static Integer toInteger(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static java.time.Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	/** View의 TEXT[] 컬럼을 읽는다. NULL이면 빈 목록으로 정규화한다. */
	private static List<String> toStringList(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		try {
			Object raw = array.getArray();
			if (raw instanceof Object[] values) {
				return Arrays.stream(values)
						.filter(java.util.Objects::nonNull)
						.map(Object::toString)
						.toList();
			}
			return List.of();
		} finally {
			array.free();
		}
	}
}
