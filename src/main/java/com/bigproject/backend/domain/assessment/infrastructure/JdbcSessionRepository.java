package com.bigproject.backend.domain.assessment.infrastructure;

import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblemReference;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SlotState;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 검증 세션의 <b>유일한 영속성 출구</b>. 조회·커서 이동·슬롯 확정을 전부 여기서 한다.
 *
 * <p>JPA가 아니라 JdbcTemplate을 쓰는 이유는 {@code problem_stage} 때문이다. 이 테이블은 한 행이
 * 질문·힌트1·힌트2 세 슬롯을 담고 각 슬롯이 컬럼 넷(답변·점수·통과·시각)을 갖는데, CHECK가 그 넷을
 * "전부 NULL이거나 전부 채움"으로 강제한다. 엔티티로 열어 두면 어느 필드 하나만 건드린 코드가
 * 런타임 CHECK 위반으로 터진다 — <b>슬롯 단위 UPDATE 하나</b>로 묶어야 그 사고가 구조적으로 막힌다.
 *
 * <h2>슬롯 컬럼은 접두어만 다르다</h2>
 *
 * <p>{@code question_} · {@code first_hint_} · {@code second_hint_}로 접두어만 갈리므로 SQL을 세 벌
 * 쓰지 않고 {@link AnswerSlot#columnPrefix()}로 조립한다. 접두어는 enum이 만든 값이라 외부 입력이
 * 섞이지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcSessionRepository {

	/** 세션이 아직 살아 있는 상태. 이 셋 밖이면 끝난 세션이라 쓰기를 받지 않는다. */
	private static final String LIVE_STATUSES = "('READY', 'IN_PROGRESS', 'PAUSED')";

	private static final String HEAD_COLUMNS = """
			SELECT s.session_id, s.org_id, s.attempt_id, a.user_id, a.assessment_round_id,
			       a.attempt_type, s.status, s.current_problem_id, s.current_problem_stage_id,
			       s.started_at, s.policy_time_limit_at, a.review_due_at, a.source_submission_id
			  FROM assessment_session s
			  JOIN measurement_attempt a ON a.attempt_id = s.attempt_id
			""";

	private final JdbcTemplate jdbc;

	/**
	 * 지금 이어서 할 세션 하나. <b>진행 중인 것이 먼저다</b> — 새로고침·재접속 복귀가 이 우선순위로
	 * 해결된다(요구사항 4). 진행 중인 것이 없으면 시작할 수 있는 세션을 준다.
	 *
	 * <p>다시 보기를 1차보다 앞에 두는 이유: 다시 보기는 마감({@code review_due_at})이 붙어 있어
	 * 미루면 사라지는 쪽이다. 1차는 이미 끝나 세션이 남아 있지 않거나 응시 창 안에 있다.
	 */
	public Optional<SessionHead> findCurrent(UUID userId) {
		List<SessionHead> found = jdbc.query(HEAD_COLUMNS + """
				 WHERE a.user_id = ?
				   AND s.status IN """ + LIVE_STATUSES + """
				 ORDER BY (s.status = 'IN_PROGRESS') DESC,
				          (a.attempt_type = 'REVIEW') DESC,
				          s.updated_at DESC
				 LIMIT 1
				""", headMapper(), userId);
		return found.stream().findFirst();
	}

	/** 소유자 확인을 조회에 붙인다 — 남의 세션은 "없음"으로 보인다. */
	public Optional<SessionHead> findOwned(UUID sessionId, UUID userId) {
		List<SessionHead> found = jdbc.query(HEAD_COLUMNS + " WHERE s.session_id = ? AND a.user_id = ?",
				headMapper(), sessionId, userId);
		return found.stream().findFirst();
	}

	/**
	 * 세션의 문제 전부를 단계·근거·코드 원문까지 채워서 돌려준다. AI 요청의 {@code problems[]}가
	 * 그대로 이것이고 화면 응답도 여기서 만든다.
	 *
	 * <p>문제는 <b>이 세션에 단계가 깔린 것만</b> 고른다. {@code generation_status='NOT_GENERATED'}인
	 * 문제에는 단계를 만들지 않으므로(정의서) 3개 미만일 수 있고, 화면의 "문제 n/3"은 이 개수를 써야 한다.
	 */
	public List<SessionProblem> findProblems(UUID sessionId, UUID submissionId) {
		Map<UUID, List<SessionStage>> stagesByProblem = new HashMap<>();
		for (SessionStage stage : findStages(sessionId)) {
			stagesByProblem.computeIfAbsent(stage.problemId(), key -> new ArrayList<>()).add(stage);
		}
		if (stagesByProblem.isEmpty()) {
			return List.of();
		}

		Map<String, String> codeBySnippetKey = findCodeSnippets(submissionId);
		Map<UUID, List<SessionProblemReference>> referencesByProblem = findReferences(sessionId);

		return jdbc.query("""
				SELECT DISTINCT p.problem_id, p.problem_no, p.title, p.problem_type, p.priority,
				       p.question_focus_item_id, p.teaches_id, p.source_snippet_key, p.code_language,
				       p.source_path, p.source_line_start, p.source_line_end, p.code_snippet_hash,
				       p.extractor_version
				  FROM assessment_problem p
				  JOIN problem_stage ps ON ps.problem_id = p.problem_id AND ps.session_id = ?
				 ORDER BY p.problem_no
				""", (rs, rowNum) -> {
			UUID problemId = rs.getObject("problem_id", UUID.class);
			String snippetKey = rs.getString("source_snippet_key");
			String code = codeBySnippetKey.getOrDefault(snippetKey, "");
			return new SessionProblem(
					problemId,
					rs.getInt("problem_no"),
					rs.getString("title"),
					rs.getString("problem_type"),
					rs.getBigDecimal("priority"),
					uuidText(rs, "question_focus_item_id"),
					rs.getObject("teaches_id", UUID.class),
					snippetKey,
					rs.getString("code_language"),
					rs.getString("source_path"),
					rs.getInt("source_line_start"),
					rs.getInt("source_line_end"),
					rs.getString("code_snippet_hash"),
					(Integer) rs.getObject("extractor_version"),
					code,
					// contentHash는 submission.code_snippets에 없다(실측). AI 정의가
					// "codeSnippet 전체의 sha256"이므로 같은 규칙으로 여기서 계산한다.
					Sha256.hex(code),
					referencesByProblem.getOrDefault(problemId, List.of()),
					stagesByProblem.getOrDefault(problemId, List.of()));
		}, sessionId);
	}

	/** 세션의 단계 전부를 문제 순 · 축 순으로. 커서 복원과 transcript 재구성이 이것으로 된다. */
	public List<SessionStage> findStages(UUID sessionId) {
		return jdbc.query("""
				SELECT ps.problem_stage_id, ps.problem_id, p.problem_no, ps.axis_code,
				       ps.question_sequence_no, ps.question_text, ps.first_hint_text, ps.second_hint_text,
				       ps.status, ps.row_version,
				       ps.question_answer_text, ps.question_score, ps.question_passed, ps.question_answered_at,
				       ps.first_hint_answer_text, ps.first_hint_score, ps.first_hint_passed,
				       ps.first_hint_answered_at, ps.first_hint_presented_at,
				       ps.second_hint_answer_text, ps.second_hint_score, ps.second_hint_passed,
				       ps.second_hint_answered_at, ps.second_hint_presented_at
				  FROM problem_stage ps
				  JOIN assessment_problem p ON p.problem_id = ps.problem_id
				 WHERE ps.session_id = ?
				 ORDER BY p.problem_no, ps.question_sequence_no
				""", stageMapper(), sessionId);
	}

	public Optional<SessionStage> findStage(UUID problemStageId) {
		List<SessionStage> found = jdbc.query("""
				SELECT ps.problem_stage_id, ps.problem_id, p.problem_no, ps.axis_code,
				       ps.question_sequence_no, ps.question_text, ps.first_hint_text, ps.second_hint_text,
				       ps.status, ps.row_version,
				       ps.question_answer_text, ps.question_score, ps.question_passed, ps.question_answered_at,
				       ps.first_hint_answer_text, ps.first_hint_score, ps.first_hint_passed,
				       ps.first_hint_answered_at, ps.first_hint_presented_at,
				       ps.second_hint_answer_text, ps.second_hint_score, ps.second_hint_passed,
				       ps.second_hint_answered_at, ps.second_hint_presented_at
				  FROM problem_stage ps
				  JOIN assessment_problem p ON p.problem_id = ps.problem_id
				 WHERE ps.problem_stage_id = ?
				""", stageMapper(), problemStageId);
		return found.stream().findFirst();
	}

	/**
	 * 세션을 시작한다. {@code READY → IN_PROGRESS}이며 인트로 동의를 함께 남긴다.
	 *
	 * <p>동의 기록이 선택이 아닌 이유: 정의서가 "IN_PROGRESS 또는 started_at이 있으면
	 * intro_acknowledged_at·intro_notice_version이 필수"라고 못박고 있다. 상태만 옮기고 동의를 비우면
	 * 정의서와 어긋난 행이 남는다.
	 *
	 * <p>커서는 첫 문제의 L1로 세운다. {@code READY}에서만 갱신하므로 두 번 불러도 진행 중인 세션의
	 * 커서를 처음으로 되돌리지 않는다.
	 *
	 * @return 실제로 시작된 경우 1
	 */
	public int start(UUID sessionId, int noticeVersion, Instant timeLimitAt) {
		return jdbc.update("""
				UPDATE assessment_session s
				   SET status = 'IN_PROGRESS',
				       started_at = COALESCE(s.started_at, now()),
				       intro_acknowledged_at = COALESCE(s.intro_acknowledged_at, now()),
				       intro_notice_version = COALESCE(s.intro_notice_version, ?),
				       policy_time_limit_at = COALESCE(s.policy_time_limit_at, ?),
				       current_problem_id = COALESCE(s.current_problem_id, first.problem_id),
				       current_problem_stage_id = COALESCE(s.current_problem_stage_id, first.problem_stage_id),
				       last_saved_at = now(), updated_at = now(), row_version = s.row_version + 1
				  FROM (SELECT ps.problem_id, ps.problem_stage_id
				          FROM problem_stage ps
				          JOIN assessment_problem p ON p.problem_id = ps.problem_id
				         WHERE ps.session_id = ?
				         ORDER BY p.problem_no, ps.question_sequence_no
				         LIMIT 1) AS first
				 WHERE s.session_id = ? AND s.status = 'READY'
				""", noticeVersion, timestamp(timeLimitAt), sessionId, sessionId);
	}

	/** 커서를 옮기고 저장 시각을 남긴다. 답변 트랜잭션 끝에서 부른다. */
	public void moveCursor(UUID sessionId, UUID problemId, UUID problemStageId) {
		jdbc.update("""
				UPDATE assessment_session
				   SET current_problem_id = ?, current_problem_stage_id = ?,
				       last_saved_at = now(), updated_at = now(), row_version = row_version + 1
				 WHERE session_id = ?
				""", problemId, problemStageId, sessionId);
	}

	/**
	 * 세션을 닫는다. 답한 데까지는 그대로 두고 남은 단계만 {@code NOT_ANSWERED}로 표시한다 —
	 * 정의서 §6 "70분 초과는 지우지 않는다. 답한 문제까지는 그대로 결과에 들어간다".
	 *
	 * <p>손도 대지 않은 단계만 옮기는 이유는 {@code ck_problem_stage_status_2}다. NOT_ANSWERED는
	 * 슬롯 아홉 개가 전부 NULL일 것을 요구하므로, 답이 하나라도 있는 단계는 IN_PROGRESS로 남긴다.
	 */
	public void end(UUID sessionId, String endReasonCode, String endedAxisCode) {
		jdbc.update("""
				UPDATE problem_stage
				   SET status = 'NOT_ANSWERED', updated_at = now(), row_version = row_version + 1
				 WHERE session_id = ?
				   AND status IN ('PREPARED', 'IN_PROGRESS')
				   AND question_answer_text IS NULL
				   AND first_hint_answer_text IS NULL
				   AND second_hint_answer_text IS NULL
				""", sessionId);
		jdbc.update("""
				UPDATE assessment_session
				   SET status = CASE WHEN ? = 'POLICY_TIME_LIMIT_EXCEEDED' THEN 'INTERRUPTED' ELSE 'COMPLETED' END,
				       end_reason_code = ?, ended_axis_code = ?, ended_at = now(),
				       last_saved_at = now(), updated_at = now(), row_version = row_version + 1
				 WHERE session_id = ? AND status IN """ + LIVE_STATUSES,
				endReasonCode, endReasonCode, endedAxisCode, sessionId);
		jdbc.update("""
				UPDATE measurement_attempt a
				   SET status = 'COMPLETED', terminal_reason_code = ?, terminal_at = now(), updated_at = now()
				  FROM assessment_session s
				 WHERE s.session_id = ? AND a.attempt_id = s.attempt_id
				   AND a.status NOT IN ('COMPLETED', 'FAILED', 'EXPIRED')
				""", "POLICY_TIME_LIMIT_EXCEEDED".equals(endReasonCode) ? "SESSION_INCOMPLETE" : "COMPLETED",
				sessionId);
	}

	/**
	 * 힌트를 연다. 표시 시각만 남기고 답변 슬롯은 건드리지 않는다 — 힌트를 열어 두고 새로고침해도
	 * 이 시각으로 {@code hintsUsed}가 복원된다.
	 *
	 * <p>{@code status}를 먼저 {@code IN_PROGRESS}로 옮기는 이유는 {@code ck_problem_stage_status_2}다.
	 * {@code PREPARED}는 통과하지만 {@code NOT_REACHED}였던 행이라면 슬롯을 채울 수 없다.
	 *
	 * @return 낙관적 잠금 충돌이면 0
	 */
	public int openHint(UUID problemStageId, AnswerSlot slot, long expectedRowVersion) {
		String column = slot.columnPrefix() + "_presented_at";
		return jdbc.update("""
				UPDATE problem_stage
				   SET %s = COALESCE(%s, now()), status = 'IN_PROGRESS',
				       updated_at = now(), row_version = row_version + 1
				 WHERE problem_stage_id = ? AND row_version = ?
				""".formatted(column, column), problemStageId, expectedRowVersion);
	}

	/**
	 * 채점 결과를 슬롯에 확정한다. 네 컬럼을 한 UPDATE로 함께 쓰는 것이 이 메서드의 존재 이유다 —
	 * CHECK가 "전부 NULL이거나 전부 채움"을 요구한다.
	 *
	 * @param stageStatus {@code PASSED} · {@code NOT_PASSED} · {@code IN_PROGRESS} 중 하나
	 * @return 낙관적 잠금 충돌이면 0
	 */
	public int applyAnswer(UUID problemStageId, AnswerSlot slot, String answerText, int score,
			boolean passed, String stageStatus, long expectedRowVersion) {
		String prefix = slot.columnPrefix();
		return jdbc.update("""
				UPDATE problem_stage
				   SET %s_answer_text = ?, %s_score = ?, %s_passed = ?, %s_answered_at = now(),
				       status = ?, updated_at = now(), row_version = row_version + 1
				 WHERE problem_stage_id = ? AND row_version = ?
				""".formatted(prefix, prefix, prefix, prefix),
				answerText, score, passed, stageStatus, problemStageId, expectedRowVersion);
	}

	/** 답변 단위 이탈·첫 타이핑 지연을 누적한다. 세션 합계도 같은 트랜잭션에서 함께 올린다. */
	public void recordAway(UUID sessionId, UUID problemStageId, AnswerSlot slot, int awaySeconds) {
		String prefix = slot.columnPrefix();
		jdbc.update("""
				UPDATE problem_stage
				   SET %s_away_count = %s_away_count + 1, %s_away_seconds = %s_away_seconds + ?,
				       updated_at = now()
				 WHERE problem_stage_id = ?
				""".formatted(prefix, prefix, prefix, prefix), awaySeconds, problemStageId);
		jdbc.update("""
				UPDATE assessment_session
				   SET window_leave_count = window_leave_count + 1,
				       total_away_seconds = total_away_seconds + ?,
				       last_window_left_at = now() - make_interval(secs => ?),
				       last_window_returned_at = now(), updated_at = now()
				 WHERE session_id = ?
				""", awaySeconds, awaySeconds, sessionId);
	}

	/** 첫 타이핑 지연은 슬롯당 한 번만 남긴다 — 이미 있으면 덮어쓰지 않는다. */
	public void recordFirstKeystroke(UUID problemStageId, AnswerSlot slot, int delayMs) {
		String column = slot.columnPrefix() + "_first_keystroke_delay_ms";
		jdbc.update("""
				UPDATE problem_stage
				   SET %s = COALESCE(%s, ?), updated_at = now()
				 WHERE problem_stage_id = ?
				""".formatted(column, column), delayMs, problemStageId);
	}

	private Map<UUID, List<SessionProblemReference>> findReferences(UUID sessionId) {
		Map<UUID, List<SessionProblemReference>> byProblem = new LinkedHashMap<>();
		jdbc.query("""
				SELECT DISTINCT r.problem_id, r.reference_type, r.display_order, r.source_path,
				       r.source_line_start, r.source_line_end, r.axis_code, r.teaches_id, r.evidence_hash
				  FROM assessment_problem_reference r
				  JOIN problem_stage ps ON ps.problem_id = r.problem_id AND ps.session_id = ?
				 ORDER BY r.problem_id, r.display_order
				""", rs -> {
			UUID problemId = rs.getObject("problem_id", UUID.class);
			byProblem.computeIfAbsent(problemId, key -> new ArrayList<>())
					.add(new SessionProblemReference(
							rs.getString("reference_type"),
							rs.getInt("display_order"),
							rs.getString("source_path"),
							(Integer) rs.getObject("source_line_start"),
							(Integer) rs.getObject("source_line_end"),
							rs.getString("axis_code"),
							rs.getObject("teaches_id", UUID.class),
							rs.getString("evidence_hash")));
		}, sessionId);
		return byProblem;
	}

	/**
	 * 제출의 코드 원문을 {@code snippetKey → code}로 편다.
	 *
	 * <p>필드 이름이 camelCase({@code snippetKey})다 — 정의서 주석은 {@code snippet_key}라고 적고 있으나
	 * 실제 저장된 JSON은 camelCase였다(실측). AI가 보내 준 문서를 그대로 넣은 자리라 AI 계약을 따른다.
	 */
	private Map<String, String> findCodeSnippets(UUID submissionId) {
		if (submissionId == null) {
			return Map.of();
		}
		Map<String, String> byKey = new HashMap<>();
		jdbc.query("""
				SELECT e->>'snippetKey' AS snippet_key, e->>'code' AS code
				  FROM submission s, jsonb_array_elements(s.code_snippets) e
				 WHERE s.submission_id = ?
				""", rs -> {
			String key = rs.getString("snippet_key");
			if (key != null) {
				byKey.put(key, rs.getString("code"));
			}
		}, submissionId);
		return byKey;
	}

	private static RowMapper<SessionHead> headMapper() {
		return (rs, rowNum) -> new SessionHead(
				rs.getObject("session_id", UUID.class),
				rs.getObject("org_id", UUID.class),
				rs.getObject("attempt_id", UUID.class),
				rs.getObject("user_id", UUID.class),
				rs.getObject("assessment_round_id", UUID.class),
				rs.getString("attempt_type"),
				rs.getString("status"),
				rs.getObject("current_problem_id", UUID.class),
				rs.getObject("current_problem_stage_id", UUID.class),
				instant(rs, "started_at"),
				instant(rs, "policy_time_limit_at"),
				instant(rs, "review_due_at"),
				rs.getObject("source_submission_id", UUID.class));
	}

	private static RowMapper<SessionStage> stageMapper() {
		return (rs, rowNum) -> new SessionStage(
				rs.getObject("problem_stage_id", UUID.class),
				rs.getObject("problem_id", UUID.class),
				rs.getInt("problem_no"),
				rs.getString("axis_code"),
				rs.getInt("question_sequence_no"),
				rs.getString("question_text"),
				rs.getString("first_hint_text"),
				rs.getString("second_hint_text"),
				rs.getString("status"),
				slot(rs, "question"),
				slot(rs, "first_hint"),
				slot(rs, "second_hint"),
				instant(rs, "first_hint_presented_at"),
				instant(rs, "second_hint_presented_at"),
				rs.getLong("row_version"));
	}

	private static SlotState slot(ResultSet rs, String prefix) throws SQLException {
		return new SlotState(
				rs.getString(prefix + "_answer_text"),
				(Short) rs.getObject(prefix + "_score"),
				(Boolean) rs.getObject(prefix + "_passed"),
				instant(rs, prefix + "_answered_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static Timestamp timestamp(Instant value) {
		return value == null ? null : Timestamp.from(value);
	}

	/** {@code question_focus_item_id}는 UUID 컬럼이지만 AI 계약은 문자열로 되돌려 받는다. */
	private static String uuidText(ResultSet rs, String column) throws SQLException {
		UUID value = rs.getObject(column, UUID.class);
		return value == null ? null : value.toString();
	}
}
