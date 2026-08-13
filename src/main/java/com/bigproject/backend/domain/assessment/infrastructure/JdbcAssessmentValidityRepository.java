package com.bigproject.backend.domain.assessment.infrastructure;

import com.bigproject.backend.domain.assessment.domain.AssessmentValidityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcAssessmentValidityRepository implements AssessmentValidityRepository {
	private final JdbcTemplate jdbcTemplate;

	@Override
	public Optional<Attempt> lockAttempt(UUID attemptId, UUID managerId) {
		String sql = """
				SELECT ma.attempt_id, ma.org_id, ma.cohort_id, ma.project_id, ma.assessment_round_id,
				  ma.user_id, pm.class_id, ma.validity_review_status,
				  ma.validity_decision_reason_code, ma.validity_decision_note,
				  ma.row_version, ma.validity_reviewed_at
				FROM measurement_attempt ma
				JOIN project_membership pm ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id
				JOIN manager_assignment mgr ON mgr.class_id = pm.class_id
				WHERE ma.attempt_id = ? AND mgr.manager_user_id = ?
				  AND mgr.status = 'ACTIVE' AND mgr.unassigned_at IS NULL
				ORDER BY pm.joined_at DESC
				LIMIT 1 FOR UPDATE OF ma
				""";
		return jdbcTemplate.query(sql, (rs, n) -> new Attempt(
				rs.getObject("attempt_id", UUID.class), rs.getObject("org_id", UUID.class),
				rs.getObject("cohort_id", UUID.class), rs.getObject("project_id", UUID.class),
				rs.getObject("assessment_round_id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getObject("class_id", UUID.class), rs.getString("validity_review_status"),
				rs.getString("validity_decision_reason_code"), rs.getString("validity_decision_note"),
				rs.getInt("row_version"), time(rs.getTimestamp("validity_reviewed_at"))), attemptId, managerId)
				.stream().findFirst();
	}

	@Override
	public int updateDecision(UUID attemptId, int rowVersion, String status, String reasonCode, String note, UUID managerId) {
		return jdbcTemplate.update("""
				UPDATE measurement_attempt
				SET validity_review_status = ?, validity_decision_reason_code = ?,
				    validity_decision_note = ?, validity_reviewed_by = ?,
				    validity_reviewed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
				    row_version = row_version + 1
				WHERE attempt_id = ? AND row_version = ?
				""", status, reasonCode, note, managerId, attemptId, rowVersion);
	}

	@Override
	public void activateInvalidReason(Attempt attempt) {
		String candidateSql = """
				INSERT INTO interview_candidate (
				  org_id, cohort_id, class_id, user_id, project_id, assessment_round_id,
				  team_id, project_membership_id, team_membership_id, source_submission_id,
				  status, detected_at
				)
				SELECT ma.org_id, ma.cohort_id, pm.class_id, ma.user_id, ma.project_id, ma.assessment_round_id,
				  tm.team_id, pm.project_membership_id, tm.team_membership_id, ma.source_submission_id,
				  'ELIGIBLE', CURRENT_TIMESTAMP
				FROM measurement_attempt ma
				JOIN project_membership pm ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id
				LEFT JOIN LATERAL (
				  SELECT x.team_id, x.team_membership_id FROM team_membership x
				  WHERE x.project_membership_id = pm.project_membership_id
				  ORDER BY x.from_at DESC LIMIT 1
				) tm ON TRUE
				WHERE ma.attempt_id = ?
				ON CONFLICT (org_id, assessment_round_id, user_id) DO NOTHING
				""";
		jdbcTemplate.update(candidateSql, attempt.attemptId());
		jdbcTemplate.update("""
				INSERT INTO interview_candidate_reason (
				  candidate_id, reason_code, evaluation_status, reason_status,
				  effective_from, source_assessment_round_id, source_attempt_id,
				  reason_summary, policy_version, detected_at
				)
				SELECT c.candidate_id, 'INVALID_ATTEMPT', 'MATCHED', 'ACTIVE', CURRENT_TIMESTAMP,
				  c.assessment_round_id, ?, '매니저가 무효 응시로 확정했습니다.', 1, CURRENT_TIMESTAMP
				FROM interview_candidate c
				WHERE c.org_id = ? AND c.assessment_round_id = ? AND c.user_id = ?
				  AND NOT EXISTS (
				    SELECT 1 FROM interview_candidate_reason r
				    WHERE r.candidate_id = c.candidate_id AND r.reason_code = 'INVALID_ATTEMPT'
				      AND r.reason_status = 'ACTIVE'
				  )
				""", attempt.attemptId(), attempt.organizationId(), attempt.assessmentRoundId(), attempt.userId());
	}

	@Override
	public void resolveInvalidReason(Attempt attempt) {
		jdbcTemplate.update("""
				UPDATE interview_candidate_reason r
				SET reason_status = 'RESOLVED', effective_to = CURRENT_TIMESTAMP,
				    resolution_code = 'VALIDITY_RESTORED', updated_at = CURRENT_TIMESTAMP,
				    row_version = row_version + 1
				FROM interview_candidate c
				WHERE r.candidate_id = c.candidate_id AND r.reason_code = 'INVALID_ATTEMPT'
				  AND r.reason_status = 'ACTIVE' AND c.org_id = ?
				  AND c.assessment_round_id = ? AND c.user_id = ?
				""", attempt.organizationId(), attempt.assessmentRoundId(), attempt.userId());
	}

	@Override
	public void insertAudit(Attempt before, Attempt after, UUID managerId, UUID requestId) {
		String beforeJson = json(before.validityStatus(), before.decisionReasonCode(), before.rowVersion());
		String afterJson = json(after.validityStatus(), after.decisionReasonCode(), after.rowVersion());
		UUID auditId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		String integrity = sha256(auditId + "|ASSESSMENT.VALIDITY_REVIEWED|" + before.attemptId() + "|" + requestId + "|" + now);
		jdbcTemplate.update("""
				INSERT INTO audit_log (
				  audit_id, org_id, actor_user_id, actor_type, event_code, action,
				  target_type, target_id, result, before_snapshot, after_snapshot,
				  request_id, trace_id, occurred_at, integrity_hash
				) VALUES (?, ?, ?, 'USER', 'ASSESSMENT.VALIDITY_REVIEWED', '무효 응시 판정',
				  'MEASUREMENT_ATTEMPT', ?, 'SUCCESS', ?::jsonb, ?::jsonb, ?, ?, ?, ?)
				""", auditId, before.organizationId(), managerId, before.attemptId().toString(),
				beforeJson, afterJson, requestId, requestId.toString(), now, integrity);
	}

	private String json(String status, String reason, int rowVersion) {
		return "{\"validityReviewStatus\":\"" + status + "\",\"decisionReasonCode\":"
				+ (reason == null ? "null" : "\"" + reason + "\"") + ",\"rowVersion\":" + rowVersion + "}";
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private OffsetDateTime time(Timestamp value) {
		return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
	}
}
