package com.bigproject.backend.domain.notification.infrastructure;

import com.bigproject.backend.domain.notification.domain.ManagerNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcManagerNotificationRepository implements ManagerNotificationRepository {
	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<InboxRow> findInbox(UUID managerId, UUID cohortId) {
		String sql = """
				                                WITH assigned AS (
				                                  SELECT ma.class_id
				                                  FROM manager_assignment ma JOIN class c ON c.class_id = ma.class_id
				                                  WHERE ma.manager_user_id = ? AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
				                                    AND c.cohort_id = ?
				                                ), attendance AS (
                                      SELECT 'ATTENDANCE:' || a.assessment_round_id || ':' || a.user_id AS item_id,
                                        CASE
                                          WHEN a.nudge_reason_code = 'TEAM_SUBMISSION_MISSING' THEN 'SUBMISSION_MISSING'
                                          WHEN a.nudge_reason_code = 'TEAM_ANALYSIS_FAILED' THEN 'ANALYSIS_FAILED'
                                          WHEN a.nudge_reason_code = 'INDIVIDUAL_ASSESSMENT_NOT_STARTED' THEN 'ASSESSMENT_NOT_STARTED'
                                          WHEN a.primary_terminal_reason_code = 'NOT_ATTENDED' THEN 'ABSENT'
                                          WHEN a.latest_review_attempt_id IS NOT NULL
                                            AND a.latest_review_status NOT IN ('COMPLETED', 'EXPIRED') THEN 'REVIEW'
                                          ELSE 'ASSESSMENT'
                                        END AS item_type,
                                        a.project_id, a.assessment_round_id, a.class_id, a.team_id, a.user_id,
                                        COALESCE(u.name, t.name, a.user_id::text) AS subject,
                                        COALESCE(a.primary_attempt_status, a.analysis_status, a.submission_deadline_status) AS source_status,
                                        COALESCE(a.nudge_reason_code, a.primary_terminal_reason_code) AS reason_code,
                                        CONCAT_WS(' · ', a.round_name, a.submission_deadline_status, a.analysis_status,
                                          a.primary_attempt_status, a.latest_review_status) AS evidence,
                                        CASE WHEN a.nudge_reason_code IN ('TEAM_SUBMISSION_MISSING','TEAM_ANALYSIS_FAILED')
                                          THEN a.submission_due_at ELSE a.primary_assessment_close_at END AS deadline_at,
                                        a.as_of_at AS occurred_at,
                                        CASE
                                          WHEN a.primary_terminal_reason_code = 'NOT_ATTENDED' THEN FALSE
                                          WHEN a.latest_review_attempt_id IS NOT NULL
                                            AND a.latest_review_status NOT IN ('COMPLETED', 'EXPIRED') THEN FALSE
                                          ELSE NOT a.nudge_eligible
                                        END AS resolved,
                                        a.nudge_eligible AS reminder_eligible
                                      FROM assessment_round_attendance a
                                      JOIN assigned sc ON sc.class_id = a.class_id
                                      LEFT JOIN app_user u ON u.user_id = a.user_id
                                      LEFT JOIN team t ON t.team_id = a.team_id
                                      WHERE a.nudge_reason_code IS NOT NULL
                                         OR a.latest_review_attempt_id IS NOT NULL
                                         OR a.primary_terminal_reason_code = 'NOT_ATTENDED'
                                    ), invalid_attempt AS (
				  SELECT 'INVALID:' || v.attempt_id, 'INVALID_ATTEMPT', v.project_id,
				    v.assessment_round_id, v.class_id, v.team_id, v.target_user_id,
				    v.target_user_name, v.validity_review_status, v.validity_trigger_reason_code,
				    CONCAT_WS(' · ', v.assessment_round_name, v.validity_trigger_reason_code, v.terminal_reason_code),
				    NULL::timestamptz, v.attempt_updated_at,
				    v.validity_review_status <> 'PENDING', FALSE
				  FROM manager_invalid_attempt_review_view v
				  WHERE v.manager_user_id = ? AND v.cohort_id = ?
				), pending_interview AS (
				  SELECT 'INTERVIEW:' || COALESCE(v.interview_id, v.candidate_id), 'INTERVIEW', v.project_id,
				    v.assessment_round_id, v.class_id, v.team_id, v.target_user_id,
				    v.target_user_name, COALESCE(v.interview_status, v.candidate_status),
				    CASE WHEN v.active_matched_reason_codes IS NULL THEN NULL
				      ELSE array_to_string(v.active_matched_reason_codes, ',') END,
				    CONCAT_WS(' · ', v.assessment_round_name, v.class_name, v.team_name),
				    v.planned_at, v.candidate_detected_at,
				    COALESCE(v.interview_status = 'COMPLETED', FALSE), FALSE
				  FROM manager_interview_list_view v
				  WHERE v.manager_user_id = ? AND v.cohort_id = ?
				), reminder AS (
				  SELECT 'REMINDER:' || rd.dispatch_id, 'REMINDER', p.project_id,
				    rd.assessment_round_id, pm.class_id, rd.team_id, rd.user_id,
				    u.name, rd.status, rd.reason_code,
				    CONCAT_WS(' · ', rd.reason_code, rd.channel, rd.status),
				    NULL::timestamptz, rd.requested_at,
				    rd.status IN ('SENT','FAILED','SUPPRESSED'), FALSE
				  FROM reminder_dispatch rd
				  JOIN project_assessment_round ar ON ar.assessment_round_id = rd.assessment_round_id
				  JOIN project p ON p.project_id = ar.project_id AND p.cohort_id = ?
				  JOIN project_membership pm ON pm.project_id = p.project_id AND pm.user_id = rd.user_id
				  JOIN manager_assignment ma ON ma.class_id = pm.class_id
				    AND ma.manager_user_id = ? AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
				  JOIN app_user u ON u.user_id = rd.user_id
				)
				SELECT * FROM attendance
				UNION ALL SELECT * FROM invalid_attempt
				UNION ALL SELECT * FROM pending_interview
				UNION ALL SELECT * FROM reminder
				""";
		return jdbcTemplate.query(sql, this::mapInbox,
				managerId, cohortId, managerId, cohortId, managerId, cohortId, cohortId, managerId);
	}

	@Override
	public List<ReminderTarget> findEligibleTargets(
			UUID managerId, UUID cohortId, UUID assessmentRoundId, UUID teamId, UUID traineeId, String reasonCode) {
		String sql = """
				SELECT DISTINCT a.user_id, a.team_id
				FROM assessment_round_attendance a
				JOIN manager_assignment ma ON ma.class_id = a.class_id
				WHERE ma.manager_user_id = ? AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
				  AND a.cohort_id = ? AND a.assessment_round_id = ?
				  AND a.nudge_eligible = TRUE AND a.nudge_reason_code = ?
				  AND (?::uuid IS NULL OR a.team_id = ?::uuid)
				  AND (?::uuid IS NULL OR a.user_id = ?::uuid)
				ORDER BY a.user_id
				""";
		return jdbcTemplate.query(sql,
				(rs, n) -> new ReminderTarget(rs.getObject("user_id", UUID.class), rs.getObject("team_id", UUID.class)),
				managerId, cohortId, assessmentRoundId, reasonCode, teamId, teamId, traineeId, traineeId);
	}

	@Override
	public Optional<ReminderBatch> findReminderBatch(UUID organizationId, UUID managerId, String idempotencyKey) {
		String sql = """
				SELECT dispatch_id, dispatch_batch_id, user_id, status, request_fingerprint
				FROM reminder_dispatch
				WHERE org_id = ? AND requested_by = ? AND request_idempotency_key = ?
				ORDER BY created_at, dispatch_id
				""";
		List<DispatchWithBatch> rows = jdbcTemplate.query(sql, (rs, n) -> new DispatchWithBatch(
				rs.getObject("dispatch_batch_id", UUID.class), rs.getString("request_fingerprint"),
				new Dispatch(rs.getObject("dispatch_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("status"))),
				organizationId, managerId, idempotencyKey);
		if (rows.isEmpty()) return Optional.empty();
		return Optional.of(new ReminderBatch(rows.get(0).batchId(), rows.get(0).fingerprint(),
				rows.stream().map(DispatchWithBatch::dispatch).toList()));
	}

	@Override
	public void lockIdempotencyKey(UUID organizationId, UUID managerId, String idempotencyKey) {
		jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {
		}, organizationId + ":" + managerId + ":" + idempotencyKey);
	}

	@Override
	public void insertReminder(
			UUID dispatchId, UUID batchId, UUID organizationId, UUID managerId,
			UUID assessmentRoundId, UUID teamId, UUID userId, String reasonCode,
			String idempotencyKey, String fingerprint, UUID requestId) {
		String sql = """
				INSERT INTO reminder_dispatch (
				  dispatch_id, assessment_round_id, team_id, user_id, org_id,
				  trigger_type, reason_code, message_template_code, dispatch_batch_id,
				  request_idempotency_key, request_fingerprint, request_id,
				  channel, status, requested_by, dedupe_key
				) VALUES (?, ?, ?, ?, ?, 'USER_REQUESTED', ?, ?, ?, ?, ?, ?, 'EMAIL', 'PENDING', ?, ?)
				""";
		jdbcTemplate.update(sql, dispatchId, assessmentRoundId, teamId, userId, organizationId,
				reasonCode, reasonCode + "_V1", batchId, idempotencyKey, fingerprint,
				requestId, managerId, "USER_REQUESTED:" + organizationId + ":" + managerId + ":" + idempotencyKey + ":" + userId);
	}

	private InboxRow mapInbox(ResultSet rs, int rowNum) throws SQLException {
		return new InboxRow(rs.getString("item_id"), rs.getString("item_type"),
				rs.getObject("project_id", UUID.class), rs.getObject("assessment_round_id", UUID.class),
				rs.getObject("class_id", UUID.class), rs.getObject("team_id", UUID.class),
				rs.getObject("user_id", UUID.class), rs.getString("subject"), rs.getString("source_status"),
				rs.getString("reason_code"), rs.getString("evidence"), time(rs.getTimestamp("deadline_at")),
				time(rs.getTimestamp("occurred_at")), rs.getBoolean("resolved"), rs.getBoolean("reminder_eligible"));
	}

	private OffsetDateTime time(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
	}

	private record DispatchWithBatch(UUID batchId, String fingerprint, Dispatch dispatch) { }
}
