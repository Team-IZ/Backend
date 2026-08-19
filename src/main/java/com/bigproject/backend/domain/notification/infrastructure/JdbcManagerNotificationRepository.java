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

	/**
	 * 38차 R1 — {@code latest_review_status}만으로는 REVIEW를 만료시킬 수 없었다.
	 *
	 * <p>{@code assessment_round_attendance} 뷰는 다시 보기 상태(SESSION_READY 등)만 줄 뿐, 그 응시의
	 * 마감({@code measurement_attempt.review_due_at})을 옮겨 담지 않는다. 그런데 {@code SESSION_READY
	 * → EXPIRED}로 상태를 바꾸는 배치가 코드베이스에 없어(전수 검색 결과 없음), 다시 보기를 시작조차
	 * 안 한 채 마감을 5개월 넘긴 응시도 영원히 REVIEW·미해소로 남았다(38차 실측 45건).
	 *
	 * <p>뷰를 고치는 대신 {@code latest_review_attempt_id}로 {@code measurement_attempt}를 직접
	 * JOIN해 마감을 읽는다 — {@code review_due_at}은 그 테이블의 실제 컬럼이라({@link
	 * com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository}가 같은 값을
	 * 이미 읽는다) 뷰 소유팀 확인 없이 여기서 바로 쓸 수 있다.
	 *
	 * <p><b>{@code sourceStatus}(1차 응시 상태)는 보지 않는다.</b> 1차가 끝났어도 다시 보기 마감
	 * 전이면 여전히 REVIEW·미해소다 — 볼 것은 다시 보기 자신의 마감뿐이다. 마감이 지나면
	 * {@code EXPIRED}로 옮기는 배치를 대신해, 조회 시점에 "마감 지난 SESSION_READY는 더 이상 할 일이
	 * 아니다"로 접어 {@code ELSE 'ASSESSMENT'}로 떨어뜨린다 — {@code MeasurementAttemptStatus.EXPIRED}가
	 * 이미 문서화해 둔 뜻("응시 창이 지나 닫혔다")을 조회 시점에 그대로 적용하는 것뿐이라 새 규칙을
	 * 만드는 것이 아니다.
	 *
	 * <p>REVIEW의 {@code deadlineAt}도 같이 바로잡는다. 종전에는 REVIEW도 1차 마감
	 * ({@code primary_assessment_close_at})을 썼는데, 다시 보기가 배정된 사람에게 의미 있는 마감은
	 * 다시 보기 자신의 마감이다 — band(급한 정도)가 "1차가 언제 끝났는가"가 아니라 "다시 보기를
	 * 언제까지 해야 하는가"를 반영하도록 한다.
	 *
	 * <p>41차 R1 — {@code SUBMISSION_MISSING}·{@code ANALYSIS_FAILED}·{@code ASSESSMENT_NOT_STARTED}가
	 * {@code resolved=true}로 접혀 미해소 인박스에 한 건도 안 실렸다. 원인은 {@code resolved}의
	 * {@code ELSE} 갈래가 {@code NOT a.nudge_eligible}이었던 것 — {@code nudge_eligible}은 "지금 새
	 * 독촉을 보내도 되는가"(이미 보냈으면 꺼진다)일 뿐 "문제가 해소됐는가"가 아닌데, {@code nudge_reason_code}가
	 * 서 있는 행(제출 누락·분석 실패·응시 미시작)까지 그 갈래로 떨어져 독촉을 한 번 보내고 나면
	 * 문제가 그대로인데도 해소로 잡혔다. {@link com.bigproject.backend.domain.notification.presentation.dto.NotificationInboxResponse.InboxItem}
	 * 문서가 이미 "{@code resolved}는 {@code reminderEligible}의 반대가 아니다"라고 못박아 둔 것과도
	 * 어긋난다. {@code nudge_reason_code}가 서 있는 동안은 {@code NOT_ATTENDED}·{@code REVIEW}와 같은
	 * 자리에서 무조건 미해소로 둔다 — 새 규칙이 아니라 옆 갈래 둘이 이미 쓰던 "조건이 서 있으면
	 * 미해소" 규칙을 그대로 적용한 것뿐이다.
	 */
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
                                            AND a.latest_review_status NOT IN ('COMPLETED', 'EXPIRED')
                                            AND (rev.review_due_at IS NULL OR rev.review_due_at > now()) THEN 'REVIEW'
                                          ELSE 'ASSESSMENT'
                                        END AS item_type,
                                        a.project_id, a.assessment_round_id, a.class_id, a.team_id, a.user_id,
                                        COALESCE(u.name, t.name, a.user_id::text) AS subject,
                                        COALESCE(a.primary_attempt_status, a.analysis_status, a.submission_deadline_status) AS source_status,
                                        COALESCE(a.nudge_reason_code, a.primary_terminal_reason_code) AS reason_code,
                                        CONCAT_WS(' · ', a.round_name, a.submission_deadline_status, a.analysis_status,
                                          a.primary_attempt_status, a.latest_review_status) AS evidence,
                                        CASE WHEN a.nudge_reason_code IN ('TEAM_SUBMISSION_MISSING','TEAM_ANALYSIS_FAILED')
                                          THEN a.submission_due_at
                                          WHEN a.latest_review_attempt_id IS NOT NULL THEN rev.review_due_at
                                          ELSE a.primary_assessment_close_at END AS deadline_at,
                                        a.as_of_at AS occurred_at,
                                        CASE
                                          WHEN a.primary_terminal_reason_code = 'NOT_ATTENDED' THEN FALSE
                                          WHEN a.latest_review_attempt_id IS NOT NULL
                                            AND a.latest_review_status NOT IN ('COMPLETED', 'EXPIRED')
                                            AND (rev.review_due_at IS NULL OR rev.review_due_at > now()) THEN FALSE
                                          WHEN a.nudge_reason_code IS NOT NULL THEN FALSE
                                          ELSE NOT a.nudge_eligible
                                        END AS resolved,
                                        a.nudge_eligible AS reminder_eligible
                                      FROM assessment_round_attendance a
                                      JOIN assigned sc ON sc.class_id = a.class_id
                                      LEFT JOIN app_user u ON u.user_id = a.user_id
                                      LEFT JOIN team t ON t.team_id = a.team_id
                                      LEFT JOIN measurement_attempt rev ON rev.attempt_id = a.latest_review_attempt_id
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
				  -- 41차 R1 — MG-03/INTERVIEW_BACKLOG(JdbcSubmissionStatusQueryRepository.interview_stat)와
				  -- 같은 "대기" 판정을 쓴다: 제외된 후보(candidate_status='EXCLUDED')는 두 화면이 같은
				  -- 사람을 두고 한쪽만 대기라고 말하지 않도록 뺀다.
				  WHERE v.manager_user_id = ? AND v.cohort_id = ? AND v.candidate_status <> 'EXCLUDED'
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
