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
				-- 32차 R8 — 안 쓴 사유는 null로 돌려준다.
				-- content가 NOT NULL이라 저장 쪽이 빈 문자열을 넣고(JdbcInterviewCompletionRepository)
				-- 여기서 되돌린다. 그래야 화면이 다시 열었을 때 입력칸이 비어 있고, 「안 쓴 것」과
				-- 「그렇게 쓴 것」이 구분된다.
				SELECT NULLIF(content, '') AS content, next_action, occurred_at
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

	/**
	 * 무효 응시 근거를 한 번에 센다 — 문제 단위 신호(무응답·복사·소요 시간)와
	 * 세션 관찰 신호(창 이탈·연결 끊김·첫 타이핑 지연, 39차 R2)를 함께 담는다.
	 *
	 * <h2>문제 단위로 센다</h2>
	 *
	 * <p>화면 문구가 "3문항 중 2문항 무응답"이고 한 회차 문제는 최대 3개다
	 * ({@code ck_assessment_problem_problem_no}). 단계 단위로 세면 문제 하나가 L1~L4로
	 * 쪼개져 "12단계 중 8단계"처럼 매니저가 읽을 수 없는 숫자가 된다.
	 *
	 * <h2>복사 판정 — 정규화 후 완전 일치</h2>
	 *
	 * <p>공백·개행을 지우고 소문자로 맞춘 뒤 비교한다. 유사도를 쓰지 않는 이유는 화면이
	 * <b>"판단은 하지 않습니다"</b>라고 밝히기 때문이다 — 애매한 유사도로 "복사했다"고
	 * 표시하면 관찰이 아니라 판정이 되고, 그 판정으로 매니저가 무효를 확정하게 된다.
	 *
	 * <p>무응답과 섞이지 않는다. {@code ck_problem_stage_status_2}가
	 * {@code NOT_ANSWERED}일 때 {@code question_answer_text}를 NULL로 강제하므로
	 * <b>답을 썼는데 질문과 같은 경우</b>만 걸린다.
	 */
	@Override
	public Optional<VoidEvidence> findVoidEvidence(UUID candidateId) {
		List<VoidEvidence> rows = jdbcTemplate.query("""
				WITH attempt AS (
				    SELECT ma.attempt_id
				    FROM interview_candidate ic
				    JOIN LATERAL (
				        SELECT x.attempt_id
				        FROM measurement_attempt x
				        WHERE x.assessment_round_id = ic.assessment_round_id
				          AND x.user_id             = ic.user_id
				          AND x.attempt_type        = 'INITIAL'
				        ORDER BY x.attempt_sequence_no DESC
				        LIMIT 1
				    ) ma ON TRUE
				    WHERE ic.candidate_id = ?
				),
				per_problem AS (
				    SELECT ps.problem_id,
				           BOOL_AND(ps.status = 'NOT_ANSWERED') AS all_unanswered,
				           BOOL_OR(ps.question_answer_text IS NOT NULL
				                   AND REGEXP_REPLACE(LOWER(ps.question_answer_text), '\\s+', '', 'g')
				                     = REGEXP_REPLACE(LOWER(ps.question_text),        '\\s+', '', 'g')) AS copied,
				           MIN(LEAST(ps.question_first_keystroke_delay_ms,
				                     ps.first_hint_first_keystroke_delay_ms,
				                     ps.second_hint_first_keystroke_delay_ms)) AS min_first_keystroke_delay_ms
				    FROM attempt a
				    JOIN assessment_session s ON s.attempt_id = a.attempt_id
				    JOIN problem_stage ps     ON ps.session_id = s.session_id
				    GROUP BY ps.problem_id
				),
				session_signals AS (
				    SELECT COALESCE(
				               EXTRACT(EPOCH FROM (s.ended_at - s.started_at)) / 60, 0) AS minutes,
				           s.window_leave_count,
				           s.connection_loss_count
				    FROM attempt a
				    JOIN assessment_session s ON s.attempt_id = a.attempt_id
				)
				SELECT COUNT(*)::int                                        AS total_questions,
				       COUNT(*) FILTER (WHERE all_unanswered)::int          AS unanswered,
				       COALESCE(BOOL_OR(copied), FALSE)                     AS copied,
				       COALESCE((SELECT ROUND(minutes)::int FROM session_signals), 0) AS duration_min,
				       COALESCE((SELECT window_leave_count FROM session_signals), 0) AS window_leave_count,
				       COALESCE((SELECT connection_loss_count FROM session_signals), 0) AS connection_loss_count,
				       MIN(min_first_keystroke_delay_ms)                    AS first_keystroke_delay_ms
				FROM per_problem
				""",
				(rs, rowNum) -> new VoidEvidence(
						rs.getInt("unanswered"),
						rs.getInt("total_questions"),
						rs.getBoolean("copied"),
						rs.getInt("duration_min"),
						rs.getInt("window_leave_count"),
						rs.getInt("connection_loss_count"),
						(Integer) rs.getObject("first_keystroke_delay_ms")),
				candidateId);

		// 문제가 하나도 없으면(미응시로 세션 자체가 안 열린 경우) 보여줄 관찰이 없다.
		return rows.stream().filter(row -> row.totalQuestions() > 0).findFirst();
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
