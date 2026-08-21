package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.domain.MySubmissionQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * TR-02 제출 현황 조회 SQL.
 *
 * <p><b>뷰를 쓰지 않는다.</b> {@code trainee_home_round_view}가 같은 원천을 읽지만 회차 단위 카드라
 * 저장소 주소·브랜치·커밋을 갖지 않는다. 제출 폼을 이전 값으로 되채우려면 그 셋이 필요하다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcMySubmissionQueryRepository implements MySubmissionQueryRepository {

	/*
	 * 프로젝트에서 시작해 회차 → 내 팀 → 현재 제출 → 분석 → 내 응시 순으로 내려간다.
	 *
	 * 회차를 하나로 좁히는 축이 `round_no DESC LIMIT 1`인 이유:
	 * uq_project_assessment_round_no_active가 (project_id, round_no)라 회차 번호는 프로젝트 안에서만
	 * 유일하고, 정의서가 MINI_PROJECT에 "활성 회차 정확히 1건"을 요구한다. 지금은 사실상 1건이지만
	 * BIG_PROJECT가 열리면 여러 건이 되므로 최신 회차를 고르는 규칙을 미리 박아 둔다.
	 *
	 * 제출은 `is_current=TRUE` 한 건만 본다 — supersede된 이전 제출은 화면이 쓸 데가 없다.
	 * 팀 단위이므로 팀원 누가 불러도 같은 제출이 나온다.
	 *
	 * 분석 job은 최신 실행 1건이다. execution_no가 재시도마다 올라간다.
	 *
	 * 응시(measurement_attempt)와 세션은 **개인 단위**라 호출자 것만 읽는다 — 같은 제출을 두고도
	 * LOCKED 여부가 사람마다 다르다. 팀원 한 명이 세션을 시작했다고 나머지가 잠기지는 않는다.
	 */
	private static final String FIND_MY_SUBMISSION = """
			SELECT r.assessment_round_id,
			       r.round_name,
			       r.status                       AS round_status,
			       r.submission_due_at,

			       s.submission_id,
			       s.method                       AS submission_method,
			       s.status                       AS submission_status,
			       s.submitted_at,
			       s.failure_reason               AS submission_failure_reason,
			       s.requested_branch,
			       s.resolved_branch,
			       s.source_commit_sha,
			       s.source_commit_message,
			       s.source_commit_committed_at,

			       repo.repo_url,
			       repo.default_branch,

			       art.original_file_name         AS artifact_file_name,
			       art.file_size_bytes            AS artifact_file_size,

			       job.status                     AS analysis_job_status,
			       job.external_job_id             AS analysis_external_job_id,
			       job.failure_code               AS analysis_failure_code,
			       job.completed_at               AS analyzed_at,

			       ca.analysis_id                 AS analysis_result_id,
			       ca.head_commit_sha             AS analysis_commit_sha,
			       ca.head_commit_message         AS analysis_commit_message,
			       ca.head_commit_committed_at    AS analysis_commit_committed_at,

			       (sess.started_at IS NOT NULL)  AS session_started,
			       ma.assessment_close_at         AS verify_closes_at
			  FROM project_assessment_round r
			  JOIN project_membership pm
			    ON pm.project_id = r.project_id
			   AND pm.user_id    = ?
			   AND pm.status     = 'ACTIVE'
			  JOIN team_membership tm
			    ON tm.project_membership_id = pm.project_membership_id
			   AND (tm.to_at IS NULL OR tm.to_at > CURRENT_TIMESTAMP)
			  JOIN team t
			    ON t.team_id    = tm.team_id
			   AND t.project_id = r.project_id
			   AND t.deleted_at IS NULL
			  LEFT JOIN submission s
			    ON s.team_id             = t.team_id
			   AND s.assessment_round_id = r.assessment_round_id
			   AND s.is_current
			  LEFT JOIN repository repo
			    ON repo.repository_id = s.repository_id
			  LEFT JOIN submission_artifact art
			    ON art.submission_id = s.submission_id
			  LEFT JOIN LATERAL (
			       SELECT j.status, j.external_job_id, j.failure_code, j.completed_at
			         FROM analysis_job j
			        WHERE j.submission_id = s.submission_id
			        ORDER BY j.execution_no DESC, j.started_at DESC, j.job_id DESC
			        LIMIT 1
			  ) job ON TRUE
			  LEFT JOIN LATERAL (
			       SELECT c.analysis_id, c.head_commit_sha, c.head_commit_message, c.head_commit_committed_at
			         FROM code_analysis c
			        WHERE c.source_submission_id = s.submission_id
			          AND c.status = 'ACTIVE'
			        ORDER BY c.created_at DESC
			        LIMIT 1
			  ) ca ON TRUE
			  LEFT JOIN measurement_attempt ma
			    ON ma.assessment_round_id = r.assessment_round_id
			   AND ma.user_id             = pm.user_id
			   AND ma.attempt_type        = 'INITIAL'
			  LEFT JOIN assessment_session sess
			    ON sess.attempt_id = ma.attempt_id
			 WHERE r.project_id = ?
			   AND r.deleted_at IS NULL
			 ORDER BY r.round_no DESC
			 LIMIT 1
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<MySubmissionRow> findMySubmission(UUID projectId, UUID userId) {
		return jdbcTemplate.query(FIND_MY_SUBMISSION, ROW_MAPPER, userId, projectId)
				.stream()
				.findFirst();
	}

	private static final RowMapper<MySubmissionRow> ROW_MAPPER = (rs, rowNum) -> new MySubmissionRow(
			rs.getObject("assessment_round_id", UUID.class),
			rs.getString("round_name"),
			rs.getString("round_status"),
			instant(rs, "submission_due_at"),

			rs.getObject("submission_id", UUID.class),
			rs.getString("submission_method"),
			rs.getString("submission_status"),
			instant(rs, "submitted_at"),
			rs.getString("submission_failure_reason"),

			rs.getString("repo_url"),
			rs.getString("requested_branch"),
			rs.getString("resolved_branch"),
			rs.getString("default_branch"),
			rs.getString("source_commit_sha"),
			rs.getString("source_commit_message"),
			instant(rs, "source_commit_committed_at"),

			rs.getString("artifact_file_name"),
			longOrNull(rs, "artifact_file_size"),

			rs.getString("analysis_job_status"),
			rs.getObject("analysis_external_job_id", UUID.class),
			rs.getObject("analysis_result_id", UUID.class),
			rs.getString("analysis_failure_code"),
			instant(rs, "analyzed_at"),
			rs.getString("analysis_commit_sha"),
			rs.getString("analysis_commit_message"),
			instant(rs, "analysis_commit_committed_at"),

			rs.getBoolean("session_started"),
			instant(rs, "verify_closes_at")
	);

	/** BIGINT → Long. {@code getLong}은 NULL을 0으로 만들어 "0바이트 파일"로 보이게 한다. */
	private static Long longOrNull(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	/** TIMESTAMPTZ → Instant. NULL 컬럼을 0 epoch로 만들지 않으려면 getTimestamp를 거쳐야 한다. */
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
