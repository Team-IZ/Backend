package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.domain.InterviewBriefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcInterviewBriefRepository implements InterviewBriefRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 뷰의 상태·플래그 + 원장의 여는 말.
	 *
	 * <p>뷰가 이미 "편집 가능한 DRAFT가 있으면 그것을, 없고 CONFIRMED만 있으면 그것을"
	 * 골라 {@code brief_id}로 내려주므로(뷰 COMMENT) 어느 버전을 볼지 여기서 다시 정하지 않는다.
	 * 여는 말만 그 {@code brief_id}로 원장에서 읽는다.
	 */
	@Override
	public Optional<BriefHeader> findHeader(UUID managerUserId, UUID orgId, UUID interviewId) {
		List<BriefHeader> rows = jdbcTemplate.query("""
				SELECT v.brief_id,
				       v.interview_id,
				       v.target_user_id,
				       v.target_user_name,
				       v.brief_persistence_status,
				       v.brief_status,
				       v.brief_type,
				       v.version_no,
				       v.is_first_interview,
				       b.opening_remark_text,
				       b.opening_remark_generated_at,
				       v.can_initialize,
				       v.can_edit,
				       v.can_confirm,
				       v.action_unavailable_reason_code
				FROM manager_interview_brief_view v
				LEFT JOIN interview_brief b
				       ON b.brief_id = v.brief_id
				WHERE v.manager_user_id = ?
				  AND v.org_id          = ?
				  AND v.interview_id    = ?
				""", this::mapHeader, managerUserId, orgId, interviewId);

		return rows.stream().findFirst();
	}

	@Override
	public List<BriefItem> findItems(UUID briefId) {
		if (briefId == null) {
			return List.of();
		}
		/*
		 * is_selected로 거르지 않는다 — 화면에 질문을 고르는 UI가 없어 전 항목을 그린다.
		 * 정렬은 suggested_order다. display_order는 선택된 항목에만 있어(CHECK가 is_selected와
		 * 짝지어 NULL을 강제) 그것으로 정렬하면 미선택 항목이 전부 뒤로 밀린다.
		 */
		return jdbcTemplate.query("""
				SELECT brief_item_id, question_text, question_rationale, suggested_order, is_selected
				FROM interview_brief_item
				WHERE brief_id = ?
				ORDER BY suggested_order, brief_item_id
				""",
				(rs, rowNum) -> new BriefItem(
						rs.getObject("brief_item_id", UUID.class),
						rs.getString("question_text"),
						rs.getString("question_rationale"),
						(Integer) rs.getObject("suggested_order"),
						rs.getBoolean("is_selected")),
				briefId);
	}

	@Override
	public List<String> findCauseCodes(UUID interviewId) {
		return jdbcTemplate.queryForList("""
				SELECT cause_code
				FROM interview_cause
				WHERE interview_id = ?
				ORDER BY cause_code
				""", String.class, interviewId);
	}

	@Override
	public Optional<ManagerRecord> findLatestRecord(UUID interviewId) {
		List<ManagerRecord> rows = jdbcTemplate.query("""
				SELECT content, next_action, occurred_at
				FROM interview_activity
				WHERE interview_id = ?
				ORDER BY occurred_at DESC, activity_id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> new ManagerRecord(
						rs.getString("content"),
						rs.getString("next_action"),
						toInstant(rs.getTimestamp("occurred_at"))),
				interviewId);

		return rows.stream().findFirst();
	}

	/**
	 * 직전 종결 면담.
	 *
	 * <p>"직전"을 회차 번호가 아니라 <b>종결 시각</b>으로 잡는다 — 회차는 프로젝트마다 번호가
	 * 다시 1부터라 번호로 이전을 정하면 다른 프로젝트의 회차와 섞인다. 현재 면담 자신은 제외한다.
	 */
	@Override
	public Optional<PriorInterview> findPriorInterview(UUID traineeUserId, UUID currentInterviewId) {
		List<PriorInterview> rows = jdbcTemplate.query("""
				SELECT i.completed_at, act.next_action
				FROM interview i
				LEFT JOIN LATERAL (
				    SELECT x.next_action
				    FROM interview_activity x
				    WHERE x.interview_id = i.interview_id
				      AND x.next_action IS NOT NULL
				    ORDER BY x.occurred_at DESC
				    LIMIT 1
				) act ON TRUE
				WHERE i.target_user_id = ?
				  AND i.interview_id  <> ?
				  AND i.status         = 'COMPLETED'
				ORDER BY i.completed_at DESC
				LIMIT 1
				""",
				(rs, rowNum) -> new PriorInterview(
						toInstant(rs.getTimestamp("completed_at")),
						rs.getString("next_action")),
				traineeUserId, currentInterviewId);

		return rows.stream().findFirst();
	}

	private BriefHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
		return new BriefHeader(
				rs.getObject("brief_id", UUID.class),
				rs.getObject("interview_id", UUID.class),
				rs.getObject("target_user_id", UUID.class),
				rs.getString("target_user_name"),
				rs.getString("brief_persistence_status"),
				rs.getString("brief_status"),
				rs.getString("brief_type"),
				(Integer) rs.getObject("version_no"),
				rs.getBoolean("is_first_interview"),
				rs.getString("opening_remark_text"),
				toInstant(rs.getTimestamp("opening_remark_generated_at")),
				rs.getBoolean("can_initialize"),
				rs.getBoolean("can_edit"),
				rs.getBoolean("can_confirm"),
				rs.getString("action_unavailable_reason_code"));
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
