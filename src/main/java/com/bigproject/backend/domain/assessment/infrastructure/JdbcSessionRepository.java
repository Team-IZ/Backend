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
 *
 * <h2>🔴 Reporting 통지 의무 (2026-08-10 합의 · 2026-08-16 확대)</h2>
 *
 * <p>아래 컬럼을 <b>폐기하거나 의미를 바꿀 때는 Reporting에 먼저 알린다.</b> 리포트 대상 선별
 * SQL이 조건으로 쓰고 있어, 말없이 바꾸면 오류 없이 <b>발행이 조용히 0건</b>이 된다. 실제로
 * 회차 응시 창({@code project_assessment_round.assessment_due_at}) 폐기가 통지 없이 넘어가
 * 그 사고가 났다(28차 R1 §2).
 *
 * <ul>
 *   <li>{@code problem_stage.status} — 값 집합과 각 값의 의미</li>
 *   <li>{@code problem_stage.problem_closed_at} · {@code problem_close_reason_code} —
 *       <b>리포트 생성의 트리거</b>다. 여기가 안 찍히면 리포트가 만들어지지 않는다</li>
 *   <li>{@code assessment_session.status} · {@code end_reason_code}</li>
 *   <li>{@code measurement_attempt.assessment_close_at} · {@code validity_review_status}</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class JdbcSessionRepository {

	/** 세션이 아직 살아 있는 상태. 이 셋 밖이면 끝난 세션이라 쓰기를 받지 않는다. */
	private static final String LIVE_STATUSES = "('READY', 'IN_PROGRESS', 'PAUSED')";

	/**
	 * 문제 종료 사유. {@code ck_problem_stage_close_reason}이 이 넷만 허용하므로 값을 늘리려면
	 * DDL을 먼저 고쳐야 한다 — 문자열을 새로 지어내면 종료 UPDATE가 CHECK 위반으로 터진다.
	 */
	/** 힌트 2개를 쓰고도 미달이라 문제를 접었다. */
	public static final String CLOSE_HINTS_EXHAUSTED = "HINTS_EXHAUSTED";

	/** AI 커서가 다른 문제로 옮겨 가 이전 문제가 끝났다. */
	public static final String CLOSE_CURSOR_MOVED = "CURSOR_MOVED";

	/** 문제별 제한 시간을 넘겨 접혔다. */
	public static final String CLOSE_PROBLEM_TIME_LIMIT = "PROBLEM_TIME_LIMIT";

	/** 세션 종료로 남은 문제가 한꺼번에 끝났다. */
	public static final String CLOSE_SESSION_ENDED = "SESSION_ENDED";

	/**
	 * 교육생에게 내보내는 문제 번호. <b>{@code assessment_problem.problem_no}가 아니다</b> —
	 * 이 세션에 단계가 깔린 문제만 골라 {@code 1..N}으로 다시 매긴 값이다.
	 *
	 * <h2>왜 원본 번호를 쓰지 않는가</h2>
	 *
	 * <p>근거를 못 찾은 개념은 {@code NOT_GENERATED} 슬롯으로 남고 그 번호에는 단계가 없다
	 * ({@code JdbcAssessmentSessionPreparer.insertStages}가 {@code GENERATED}만 대상으로 한다).
	 * 그래서 원본 번호는 <b>빈틈이 생긴다</b> — 1번이 NOT_GENERATED면 남는 번호는 2·3이고
     * {@code problemTotal}은 2다. 그 상태로 내보내면 화면이 `문제 3 / 2`를 그리고, 마지막 문제 판정
	 * ({@code problemNo == problemTotal})이 어긋나며, 무엇보다 <b>존재하지 않는 1번을 열려다
	 * {@code PROBLEM_NOT_FOUND}로 막힌다.</b> 생성된 문제만 1부터 세면 그 세 가지가 함께 사라진다.
	 *
	 * <p>원본 번호는 DB에 그대로 남아 있고 리포트·매니저 지표는 그것을 읽는다 — 여기서 바꾸는 것은
	 * <b>세션 API가 말하는 번호</b>뿐이다. 세 조회({@link #findStages} · {@link #findStage} ·
	 * {@link #findProblems})가 반드시 같은 규칙을 써야 하므로 정의를 한 자리에 둔다.
	 */
	private static final String DISPLAY_PROBLEM_NO =
			"DENSE_RANK() OVER (ORDER BY p.problem_no) AS problem_no";

	/**
	 * 문제별 20분 제한은 컬럼을 새로 두지 않는다. {@code problem_stage.question_presented_at}이
	 * "이 축을 교육생에게 처음 보여준 시각"을 이미 뜻하므로, 지금 문제의 L1 축 그 값이 곧 그 문제가
	 * 시작된 시각이다. 세션 레벨({@code policy_time_limit_at})과 달리 미리 계산해 두지 않고 매번 읽어
	 * 판단한다 — 값 하나뿐이라 조인 비용이 무시할 만하다.
	 */
	private static final String HEAD_COLUMNS = """
			SELECT s.session_id, s.org_id, s.attempt_id, a.user_id, a.assessment_round_id,
			       a.attempt_type, s.status, s.current_problem_id, s.current_problem_stage_id,
			       s.started_at, s.policy_time_limit_at, a.review_due_at, a.source_submission_id,
			       cp.question_presented_at AS current_problem_started_at,
			       a.assessment_close_at
			  FROM assessment_session s
			  JOIN measurement_attempt a ON a.attempt_id = s.attempt_id
			  LEFT JOIN LATERAL (
			      SELECT ps.question_presented_at
			      FROM problem_stage ps
			      WHERE ps.session_id = s.session_id AND ps.problem_id = s.current_problem_id
			        AND ps.axis_code = 'L1'
			  ) cp ON TRUE
			""";

	private final JdbcTemplate jdbc;

	/**
	 * 지금 이어서 할 세션 하나. <b>진행 중인 것이 먼저다</b> — 새로고침·재접속 복귀가 이 우선순위로
	 * 해결된다(요구사항 4). 진행 중인 것이 없으면 시작할 수 있는 세션을 준다.
	 *
	 * <p>다시 보기를 1차보다 앞에 두는 이유: 다시 보기는 마감({@code review_due_at})이 붙어 있어
	 * 미루면 사라지는 쪽이다. 1차는 이미 끝나 세션이 남아 있지 않거나 응시 창 안에 있다.
	 *
	 * <p><b>마감이 지난 세션은 내주지 않는다</b>(2026-08-16). 그대로 두면 화면이 "지금 이어서 할
	 * 세션"으로 그려 놓고 START에서만 409가 나서, 학생이 들어갈 수 없는 시험을 계속 권유받는다.
	 * 홈 카드가 이미 {@code ASSESSMENT_WINDOW_CLOSED} · CTA {@code NONE}으로 말하는 것과 맞춘다.
	 * 마감 컬럼이 NULL이면(창이 아직 없거나 1차라 review_due_at이 없는 경우) 막지 않는다 —
	 * {@code SessionHead#deadlineAt}이 쓰는 것과 같은 규칙이다.
	 */
	public Optional<SessionHead> findCurrent(UUID userId) {
		List<SessionHead> found = jdbc.query(HEAD_COLUMNS + """
				 WHERE a.user_id = ?
				   AND s.status IN """ + LIVE_STATUSES + """
				   AND COALESCE(CASE WHEN a.attempt_type = 'REVIEW' THEN a.review_due_at
				                     ELSE a.assessment_close_at END > now(), TRUE)
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
	 *
	 * <p>번호는 SQL에서 다시 매기지 않고 <b>단계에서 가져온다</b>({@link #DISPLAY_PROBLEM_NO}로 이미
	 * 매겨져 있다). 여기서 창 함수를 한 번 더 쓰면 같은 규칙이 두 벌이 되고, 한쪽만 고쳐지는 순간
	 * 같은 문제가 조회마다 다른 번호로 보인다.
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
			List<SessionStage> stages = stagesByProblem.getOrDefault(problemId, List.of());
			return new SessionProblem(
					problemId,
					// 원본 p.problem_no가 아니다 — 단계에 매겨진 표시 번호를 그대로 쓴다.
					stages.isEmpty() ? 0 : stages.get(0).problemNo(),
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
					stages);
		}, sessionId).stream()
				// 단계가 없는 문제는 애초에 이 목록에 들어올 수 없다(JOIN이 걸러 낸다). 그래도 방어적으로
				// 비어 있으면 건너뛴다 — 번호를 단계에서 가져오므로 근거가 없는 행을 만들 수 없다.
				.filter(problem -> !problem.stages().isEmpty())
				.toList();
	}

	/**
	 * 세션의 단계 전부를 문제 순 · 축 순으로. 커서 복원과 transcript 재구성이 이것으로 된다.
	 *
	 * <p>{@code problem_no}는 {@link #DISPLAY_PROBLEM_NO}다 — 원본 번호가 아니라 이 세션에 깔린
	 * 문제만 1부터 센 값이다.
	 */
	public List<SessionStage> findStages(UUID sessionId) {
		return jdbc.query("""
				SELECT ps.problem_stage_id, ps.problem_id, """ + DISPLAY_PROBLEM_NO + """
				       , ps.axis_code,
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

	/**
	 * 커서가 가리키는 단계 하나.
	 *
	 * <p>여기서는 {@link #DISPLAY_PROBLEM_NO}의 창 함수를 쓸 수 없다 — 결과가 한 행이라 순위가 항상
	 * 1이 된다. 같은 값을 <b>세션 전체를 세어</b> 구한다: 이 문제보다 앞선(같은 것 포함) 문제 번호가
	 * 몇 개인가가 곧 표시 번호다. {@link #findStages}의 순위와 반드시 같은 값이어야 한다 —
	 * {@code SessionGuard}가 이 번호로 다음 문제를 찾으므로 두 조회가 어긋나면 문제 하나를
	 * 건너뛰거나 같은 문제를 다시 연다.
	 */
	public Optional<SessionStage> findStage(UUID problemStageId) {
		List<SessionStage> found = jdbc.query("""
				SELECT ps.problem_stage_id, ps.problem_id,
				       (SELECT count(DISTINCT p2.problem_no)
				          FROM problem_stage ps2
				          JOIN assessment_problem p2 ON p2.problem_id = ps2.problem_id
				         WHERE ps2.session_id = ps.session_id
				           AND p2.problem_no <= p.problem_no) AS problem_no,
				       ps.axis_code,
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
	 * <p>커서는 첫 문제의 L1로 세운다. 값이 이미 있으면 {@code COALESCE}가 지키므로 두 번 불러도 진행
	 * 중인 세션의 커서를 처음으로 되돌리지 않는다.
	 *
	 * <h2>{@code PAUSED}도 받는다 (37차 R5)</h2>
	 *
	 * <p>종전에는 {@code READY}만 걸렸다. {@code PAUSED}는 살아 있는 상태({@link #LIVE_STATUSES})라
	 * 조회에는 나오는데 되살릴 경로가 없어, 그 상태의 세션은 {@code POST /start}가 조용히 0건이 되고
	 * 학생은 영영 들어가지 못했다.
	 *
	 * <p><b>시계는 되감기지 않는다.</b> 이 UPDATE가 바꾸는 값 중 시간·커서에 해당하는 것은 전부
	 * {@code COALESCE(기존값, 새값)}이라, 이미 시작된 세션에는 새 값이 들어가지 않는다. 그래서 조건만
	 * 넓혀도 진행 중이던 응시가 처음부터 다시 시작되지 않는다 — 이것이 이 확장이 안전한 이유다.
	 *
	 * @return 실제로 시작된 경우 1
	 */
	public int start(UUID sessionId, int noticeVersion, Instant timeLimitAt) {
		int updated = jdbc.update("""
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
				 WHERE s.session_id = ? AND s.status IN ('READY', 'PAUSED')
				""", noticeVersion, timestamp(timeLimitAt), sessionId, sessionId);
		stampCurrentProblemStarted(sessionId);
		return updated;
	}

	/**
	 * 지금 커서가 가리키는 축의 {@code question_presented_at}을 채운다. 이미 값이 있으면 건드리지 않는다
	 * (COALESCE) — 문제별 20분 제한의 기준점이라 다시 부를 때마다 갱신되면 시계가 계속 늘어난다.
	 *
	 * <p>{@code start()}·{@code moveCursor()} 둘 다 끝에서 부른다. 실제로 커서가 바뀌었는지 따지지
	 * 않고 매번 불러도 안전하다 — L1이 아닌 축이어도 상관없다(그 문제로 다시 들어왔을 때 이미 L1이
	 * 찍혀 있으므로 이 호출은 아무것도 하지 않는다).
	 */
	private void stampCurrentProblemStarted(UUID sessionId) {
		jdbc.update("""
				UPDATE problem_stage ps
				   SET question_presented_at = COALESCE(ps.question_presented_at, now())
				  FROM assessment_session s
				 WHERE s.session_id = ? AND ps.problem_stage_id = s.current_problem_stage_id
				""", sessionId);
	}

	/** 커서를 옮기고 저장 시각을 남긴다. 답변 트랜잭션 끝에서 부른다. */
	public void moveCursor(UUID sessionId, UUID problemId, UUID problemStageId) {
		jdbc.update("""
				UPDATE assessment_session
				   SET current_problem_id = ?, current_problem_stage_id = ?,
				       last_saved_at = now(), updated_at = now(), row_version = row_version + 1
				 WHERE session_id = ?
				""", problemId, problemStageId, sessionId);
		stampCurrentProblemStarted(sessionId);
	}

	/**
	 * 문제별 제한 시간을 넘긴 현재 문제를 접는다. 힌트를 다 쓰고도 미달일 때({@code SessionTurnStore
	 * .closeProblem})와 <b>같은 전이</b>다 — 남은 축은 NOT_REACHED로 닫고 다음 문제의 첫 축으로
	 * 커서를 옮기거나, 마지막 문제였으면 세션을 끝낸다.
	 *
	 * <p>AI가 판정한 도달 축이 없다(타임아웃이라 답을 받지 않았다) — {@code endedAxisCode}는 null이며
	 * DB CHECK({@code ck_assessment_session_ended_axis_code})가 이를 허용한다.
	 */
	public void expireCurrentProblem(UUID sessionId, UUID currentProblemId, int currentProblemNo, boolean isReview) {
		closeProblem(sessionId, currentProblemId, CLOSE_PROBLEM_TIME_LIMIT);

		SessionStage nextStage = findStages(sessionId).stream()
				.filter(stage -> !stage.problemId().equals(currentProblemId))
				.filter(stage -> stage.problemNo() > currentProblemNo)
				.findFirst()
				.orElse(null);

		if (nextStage == null) {
			end(sessionId, isReview ? "ALL_REVIEW_TARGETS_TERMINAL" : "ALL_PROBLEMS_TERMINAL", null);
			return;
		}
		moveCursor(sessionId, nextStage.problemId(), nextStage.problemStageId());
	}

	/**
	 * 세션을 닫는다. 답한 데까지는 그대로 두고 남은 단계는 종료 상태로 정리한다 —
	 * 정의서 §6 "70분 초과는 지우지 않는다. 답한 문제까지는 그대로 결과에 들어간다".
	 *
	 * <p>정리가 두 갈래인 것은 {@code ck_problem_stage_status_2} 때문이다. {@code NOT_ANSWERED}는
	 * 슬롯 아홉 개가 전부 NULL일 것을 요구하므로 <b>손도 대지 않은 단계</b>만 받고, 답이 들어간
	 * 단계는 {@code NOT_PASSED}로 닫는다({@link #closeAnsweredStages}).
	 *
	 * <p>🔴 <b>남은 문제를 한꺼번에 끝내는 자리이므로 종료 표식도 여기서 찍는다.</b> 이미 닫힌
	 * 문제는 {@code problem_closed_at}이 채워져 있어 건너뛴다.
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
		closeAnsweredStages(sessionId, null);
		stampProblemClosed(sessionId, null, CLOSE_SESSION_ENDED);
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
	 * @param gradingRequestId 이 점수를 만든 AI 호출의 멱등키({@code clientRequestId}). 판정과 <b>같은
	 *                         UPDATE</b>로 남긴다 — 따로 쓰면 채점은 저장됐는데 어느 호출이었는지는
	 *                         모르는 행이 생기고, 그 행이 정확히 원인을 되짚어야 하는 행이다(37차 R1).
	 *                         컬럼은 {@code docs/migration/2026-08-18_problem_stage_grading_request_id.sql}이
	 *                         만든다 — <b>DB에 먼저 적용해야 이 UPDATE가 돈다.</b>
	 * @return 낙관적 잠금 충돌이면 0
	 */
	public int applyAnswer(UUID problemStageId, AnswerSlot slot, String answerText, int score,
			boolean passed, String stageStatus, UUID gradingRequestId, long expectedRowVersion) {
		String prefix = slot.columnPrefix();
		return jdbc.update("""
				UPDATE problem_stage
				   SET %s_answer_text = ?, %s_score = ?, %s_passed = ?, %s_answered_at = now(),
				       %s_request_id = ?,
				       status = ?, updated_at = now(), row_version = row_version + 1
				 WHERE problem_stage_id = ? AND row_version = ?
				""".formatted(prefix, prefix, prefix, prefix, prefix),
				answerText, score, passed, gradingRequestId, stageStatus, problemStageId,
				expectedRowVersion);
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
		insertActivityLog(sessionId, problemStageId, "WINDOW_LEAVE", awaySeconds * 1000);
	}

	/**
	 * 창 이탈·연결 끊김·첫 타이핑 지연을 발생 건별로 남긴다. {@code assessment_session}·
	 * {@code problem_stage}의 누적 컬럼은 무효 응시 판정이 그대로 읽으므로 손대지 않고, 이 로그는
	 * 매니저가 "언제 몇 초씩 몇 번" 벌어졌는지 재구성하기 위한 상세 이력으로 나란히 쌓는다.
	 *
	 * <p>{@code startedAt}은 실측이 아니라 근사치다 — 클라이언트가 복귀·재연결 시점에 지속 시간만
	 * 보내므로 {@code now() - duration}으로 역산한다({@code last_window_left_at}과 같은 방식).
	 * {@code duration_ms}는 세 이벤트 유형의 단위(초 vs ms)를 밀리초 하나로 맞춘다.
	 */
	private void insertActivityLog(UUID sessionId, UUID problemStageId, String eventType, int durationMs) {
		jdbc.update("""
				INSERT INTO problem_stage_activity_log
				       (problem_stage_id, session_id, event_type, started_at, duration_ms)
				VALUES (?, ?, ?, now() - make_interval(secs => ?::numeric / 1000), ?)
				""", problemStageId, sessionId, eventType, durationMs, durationMs);
	}

	/**
	 * 문제 하나를 <b>종료로 확정한다.</b> 남은 축을 전부 닫고 종료 표식을 찍는다.
	 *
	 * <h2>왜 한 메서드인가</h2>
	 *
	 * <p>종전에는 {@code markNotReached} 하나뿐이었고 <b>답이 들어간 축은 손대지 못했다.</b>
	 * 질문에만 답하고 미달인 축은 힌트 둘이 NULL이라 {@code ck_problem_stage_status_2}의 어느
	 * 종료 분기에도 들어가지 못해 {@code IN_PROGRESS}로 남았기 때문이다. 그 결과 리포트 대상
	 * 선별("{@code PREPARED}·{@code IN_PROGRESS}가 없으면 끝난 것")이 그 문제를 <b>영영 집지
	 * 못했다.</b> 2026-08-17 CHECK 완화로 그 축을 {@code NOT_PASSED}로 닫을 수 있게 됐고,
	 * 세 UPDATE가 <b>항상 함께</b> 나가야 하므로 한 메서드로 묶는다.
	 *
	 * <ol>
	 *   <li>ⓐ 손대지 않은 축 → {@code NOT_REACHED}. 그냥 두면 리포트가 "도달했는데 못 풀었다"와
	 *       "여기까지 오지도 못했다"를 구분하지 못한다</li>
	 *   <li>ⓑ 답이 들어갔지만 통과·소진 어느 쪽도 아닌 축 → {@code NOT_PASSED}</li>
	 *   <li>ⓒ 그 문제의 전 축에 {@code problem_closed_at}·{@code problem_close_reason_code}</li>
	 * </ol>
	 *
	 * <p>ⓒ가 <b>리포트 생성의 유일한 트리거</b>다. 순서가 중요하다 — ⓒ를 먼저 찍으면 아직 안 닫힌
	 * 축이 있는 상태로 대상이 되어, 리포트가 "아직 진행 중"인 단계를 그대로 서술한다.
	 *
	 * @param reasonCode {@link #CLOSE_HINTS_EXHAUSTED} · {@link #CLOSE_CURSOR_MOVED} ·
	 *                   {@link #CLOSE_PROBLEM_TIME_LIMIT} · {@link #CLOSE_SESSION_ENDED} 중 하나.
	 *                   값 집합은 {@code ck_problem_stage_close_reason}이 강제한다
	 * @return 이번 호출이 닫은 축 수(ⓐ + ⓑ). 표식만 찍힌 경우 0이다
	 */
	public int closeProblem(UUID sessionId, UUID problemId, String reasonCode) {
		int closed = jdbc.update("""
				UPDATE problem_stage
				   SET status = 'NOT_REACHED', updated_at = now(), row_version = row_version + 1
				 WHERE session_id = ? AND problem_id = ?
				   AND status IN ('PREPARED', 'IN_PROGRESS')
				   AND question_answer_text IS NULL
				   AND first_hint_answer_text IS NULL
				   AND second_hint_answer_text IS NULL
				""", sessionId, problemId);
		closed += closeAnsweredStages(sessionId, problemId);
		stampProblemClosed(sessionId, problemId, reasonCode);
		return closed;
	}

	/**
	 * 답이 들어간 채 열려 있는 축을 {@code NOT_PASSED}로 닫는다.
	 *
	 * <p>이런 축이 생기는 경로는 <b>정상 흐름</b>이다 — 질문에 미달했는데 AI 커서가 다른 자리로
	 * 옮겨 가면({@code SessionTurnStore.autoHint}가 "AI가 이 질문은 여기까지라고 판정한 것"으로
	 * 다루는 경우) 힌트를 열지 않은 채 그 축이 남는다.
	 *
	 * <p>🔴 {@code IS NOT TRUE} 세 조건은 방어다. {@code applyAnswer}가 통과한 축을
	 * {@code PASSED}로 이미 옮기므로 실제로는 걸릴 행이 없지만, 하나라도 TRUE인 행을 잡으면
	 * 완화된 CHECK가 그 UPDATE를 거절해 <b>답변 저장 트랜잭션 전체가 롤백된다.</b>
	 *
	 * @param problemId {@code null}이면 세션 전체를 대상으로 한다({@link #end} 경로)
	 */
	private int closeAnsweredStages(UUID sessionId, UUID problemId) {
		return jdbc.update("""
				UPDATE problem_stage
				   SET status = 'NOT_PASSED', updated_at = now(), row_version = row_version + 1
				 WHERE session_id = ?
				   AND (?::uuid IS NULL OR problem_id = ?::uuid)
				   AND status IN ('PREPARED', 'IN_PROGRESS')
				   AND question_answer_text IS NOT NULL
				   AND question_passed    IS NOT TRUE
				   AND first_hint_passed  IS NOT TRUE
				   AND second_hint_passed IS NOT TRUE
				""", sessionId, problemId, problemId);
	}

	/**
	 * 종료 표식을 찍는다. <b>이미 찍힌 행은 건드리지 않아 멱등이다</b> — 같은 문제에 두 번 불려도
	 * 첫 종료 시각이 유지된다.
	 *
	 * <p>표식은 축 행에 있지만 <b>의미는 문제 단위</b>다. 한 UPDATE로 그 문제의 네 축에 같은 값을
	 * 찍으므로 "일부 축만 닫힌" 중간 상태가 생기지 않는다.
	 *
	 * @param problemId {@code null}이면 세션의 남은 문제 전부({@link #end} 경로)
	 */
	private void stampProblemClosed(UUID sessionId, UUID problemId, String reasonCode) {
		jdbc.update("""
				UPDATE problem_stage
				   SET problem_closed_at = now(), problem_close_reason_code = ?, updated_at = now()
				 WHERE session_id = ?
				   AND (?::uuid IS NULL OR problem_id = ?::uuid)
				   AND problem_closed_at IS NULL
				""", reasonCode, sessionId, problemId, problemId);
	}

	/**
	 * 연결 끊김을 <b>세션 단위로만</b> 누적한다.
	 *
	 * <p>답변 슬롯에 나누지 않는 것은 DDL 주석(v08)의 판단을 따른 것이다 — 무효 응시 판정
	 * ({@code measurement_attempt}의 {@code EXCESSIVE_CONNECTION_LOSS})이 세션 합계를 보고,
	 * 네트워크 장애는 특정 답변에 귀속시킬 성질이 아니다. 창 이탈만 슬롯에 함께 쌓는다.
	 *
	 * <p>{@code problemStageId}는 판정용 누적에는 쓰이지 않고 {@link #insertActivityLog}에만
	 * 넘어간다 — "그 순간 어떤 질문을 보고 있었는지"를 매니저 로그 조회의 맥락으로 남기기 위해서다.
	 */
	public void recordConnectionLoss(UUID sessionId, UUID problemStageId, int disconnectedSeconds) {
		jdbc.update("""
				UPDATE assessment_session
				   SET connection_loss_count = connection_loss_count + 1,
				       total_disconnected_seconds = total_disconnected_seconds + ?,
				       updated_at = now()
				 WHERE session_id = ?
				""", disconnectedSeconds, sessionId);
		insertActivityLog(sessionId, problemStageId, "CONNECTION_LOSS", disconnectedSeconds * 1000);
	}

	/**
	 * 첫 타이핑 지연은 슬롯당 한 번만 남긴다 — 이미 있으면 손대지 않는다.
	 *
	 * <p>{@code column IS NULL}을 WHERE에 넣어 두 번째부터는 UPDATE 자체가 0행이 되게 한다.
	 * 영향받은 행 수로 최초 기록 여부를 판별해, 중복 전송에는 {@link #insertActivityLog}도
	 * 부르지 않는다 — 로그도 "중복 전송이 안전하다"는 계약을 그대로 따라야 한다.
	 */
	public void recordFirstKeystroke(UUID sessionId, UUID problemStageId, AnswerSlot slot, int delayMs) {
		String column = slot.columnPrefix() + "_first_keystroke_delay_ms";
		int updated = jdbc.update("""
				UPDATE problem_stage
				   SET %s = ?, updated_at = now()
				 WHERE problem_stage_id = ? AND %s IS NULL
				""".formatted(column, column), delayMs, problemStageId);
		if (updated > 0) {
			insertActivityLog(sessionId, problemStageId, "FIRST_KEYSTROKE_DELAY", delayMs, occurredAt);
		}
	}

	/**
	 * {@code CURRICULUM_EVIDENCE}는 DDL {@code ck_assessment_problem_reference_shape}가
	 * {@code source_path}/{@code line_*}를 강제로 NULL로 두는 대신 {@code teaches_id}를 채운다
	 * (39차 R4). 그 자리를 대신할 라벨·근거 페이지를 여기서 {@code teaches}·
	 * {@code curriculum_teaches_mapping}까지 조인해 함께 가져온다 — 조인 패턴은
	 * {@code JdbcReportPayloadRepository.findTeaches}(reporting 도메인)와 같다.
	 */
	private Map<UUID, List<SessionProblemReference>> findReferences(UUID sessionId) {
		Map<UUID, List<SessionProblemReference>> byProblem = new LinkedHashMap<>();
		jdbc.query("""
				SELECT DISTINCT r.problem_id, r.reference_type, r.display_order, r.source_path,
				       r.source_line_start, r.source_line_end, r.axis_code, r.teaches_id, r.evidence_hash,
				       t.canonical_name AS teach_label,
				       m.source_pages
				  FROM assessment_problem_reference r
				  JOIN problem_stage ps ON ps.problem_id = r.problem_id AND ps.session_id = ?
				  LEFT JOIN teaches t ON t.teaches_id = r.teaches_id
				  LEFT JOIN LATERAL (
				      SELECT ARRAY(SELECT jsonb_array_elements_text(x.source_pages)::int) AS source_pages
				        FROM curriculum_teaches_mapping x
				       WHERE x.teaches_id = t.teaches_id
				         AND x.mapping_status = 'ACTIVE'
				       ORDER BY x.sequence_no
				       LIMIT 1
				  ) m ON TRUE
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
							rs.getString("evidence_hash"),
							rs.getString("teach_label"),
							nullableIntList(rs, "source_pages")));
		}, sessionId);
		return byProblem;
	}

	private static List<Integer> nullableIntList(ResultSet rs, String column) throws SQLException {
		java.sql.Array array = rs.getArray(column);
		if (array == null) {
			return null;
		}
		Integer[] boxed = (Integer[]) array.getArray();
		return java.util.Arrays.asList(boxed);
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
				rs.getObject("source_submission_id", UUID.class),
				instant(rs, "current_problem_started_at"),
				instant(rs, "assessment_close_at"));
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
				nullableShort(rs, prefix + "_score"),
				(Boolean) rs.getObject(prefix + "_passed"),
				instant(rs, prefix + "_answered_at"));
	}

	/** JDBC 드라이버가 SMALLINT를 Short 또는 Integer 어느 쪽으로 돌려줘도 같은 값으로 읽는다. */
	static Short nullableShort(ResultSet rs, String column) throws SQLException {
		Number value = (Number) rs.getObject(column);
		return value == null ? null : value.shortValue();
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
