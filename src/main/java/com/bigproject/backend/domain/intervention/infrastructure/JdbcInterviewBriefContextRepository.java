package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiRequest;
import com.bigproject.backend.domain.intervention.domain.InterviewBriefContextRepository;
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

/**
 * AI 요청 재료 조회.
 *
 * <h2>회차 수행을 고르는 규칙</h2>
 *
 * <p>공식 결과는 {@code attempt_type='INITIAL'} 수행만 쓴다 — 재시험·다시 보기를 섞으면
 * 브리프가 "그때 무엇을 못 했는가"가 아니라 "나중에 고쳐 맞힌 것"을 근거로 질문하게 된다.
 * ({@code manager_project_result_view}가 같은 규칙을 쓴다.)
 */
@Repository
@RequiredArgsConstructor
public class JdbcInterviewBriefContextRepository implements InterviewBriefContextRepository {

	private final JdbcTemplate jdbcTemplate;

	/** 후보 → 그 회차의 INITIAL 수행. 여러 조회가 공유하는 진입 경로다. */
	private static final String ATTEMPT_FROM = """
			FROM interview_candidate ic
			JOIN LATERAL (
			    SELECT x.*
			    FROM measurement_attempt x
			    WHERE x.assessment_round_id = ic.assessment_round_id
			      AND x.user_id             = ic.user_id
			      AND x.attempt_type        = 'INITIAL'
			    ORDER BY x.attempt_sequence_no DESC
			    LIMIT 1
			) ma ON TRUE
			""";

	@Override
	public Optional<InterviewBriefAiRequest.Target> findTarget(UUID candidateId) {
		List<InterviewBriefAiRequest.Target> rows = jdbcTemplate.query("""
				SELECT u.name           AS user_name,
				       c.name           AS class_name,
				       p.name           AS project_name,
				       p.project_category,
				       r.round_name,
				       r.analysis_role_code
				FROM interview_candidate ic
				JOIN app_user u ON u.user_id = ic.user_id
				JOIN class    c ON c.class_id = ic.class_id
				JOIN project  p ON p.project_id = ic.project_id
				JOIN project_assessment_round r ON r.assessment_round_id = ic.assessment_round_id
				WHERE ic.candidate_id = ?
				""",
				(rs, rowNum) -> new InterviewBriefAiRequest.Target(
						rs.getString("user_name"),
						rs.getString("class_name"),
						rs.getString("project_name"),
						rs.getString("project_category"),
						rs.getString("round_name"),
						rs.getString("analysis_role_code")),
				candidateId);

		return rows.stream().findFirst();
	}

	@Override
	public List<ReasonRow> findRiskReasons(UUID candidateId) {
		return jdbcTemplate.query("""
				SELECT candidate_reason_id, reason_code, evaluation_status, not_applicable_reason_code,
				       reason_summary, detected_at, source_problem_stage_id
				FROM interview_candidate_reason
				WHERE candidate_id  = ?
				  AND reason_status = 'ACTIVE'
				ORDER BY detected_at
				""",
				(rs, rowNum) -> new ReasonRow(
						rs.getObject("candidate_reason_id", UUID.class),
						rs.getString("reason_code"),
						rs.getString("evaluation_status"),
						rs.getString("not_applicable_reason_code"),
						rs.getString("reason_summary"),
						toInstant(rs.getTimestamp("detected_at")),
						rs.getObject("source_problem_stage_id", UUID.class)),
				candidateId);
	}

	@Override
	public Optional<InterviewBriefAiRequest.ValidityReview> findValidityReview(UUID candidateId) {
		List<InterviewBriefAiRequest.ValidityReview> rows = jdbcTemplate.query("""
				SELECT ma.validity_review_status,
				       ma.validity_trigger_reason_code,
				       ma.validity_decision_reason_code,
				       ma.validity_decision_note
				""" + ATTEMPT_FROM + "WHERE ic.candidate_id = ?\n",
				(rs, rowNum) -> new InterviewBriefAiRequest.ValidityReview(
						rs.getString("validity_review_status"),
						rs.getString("validity_trigger_reason_code"),
						rs.getString("validity_decision_reason_code"),
						rs.getString("validity_decision_note")),
				candidateId);

		return rows.stream().findFirst();
	}

	@Override
	public Optional<AttemptRow> findAttempt(UUID candidateId) {
		/*
		 * 세션은 LEFT JOIN이다 — NOT_ATTENDED면 세션 자체가 열리지 않아 행이 없다.
		 * 그 경우 sessionEndReasonCode가 null이고 AI는 "미응시 사유를 묻는 질문"으로 전환한다.
		 */
		List<AttemptRow> rows = jdbcTemplate.query("""
				SELECT ma.attempt_id,
				       s.session_id,
				       ma.attempt_type,
				       ma.status AS attempt_status,
				       ma.terminal_reason_code,
				       s.end_reason_code
				""" + ATTEMPT_FROM + """
				LEFT JOIN assessment_session s ON s.attempt_id = ma.attempt_id
				WHERE ic.candidate_id = ?
				""",
				(rs, rowNum) -> new AttemptRow(
						rs.getObject("attempt_id", UUID.class),
						rs.getObject("session_id", UUID.class),
						rs.getString("attempt_type"),
						rs.getString("attempt_status"),
						rs.getString("terminal_reason_code"),
						rs.getString("end_reason_code")),
				candidateId);

		return rows.stream().findFirst();
	}

	@Override
	public List<ProblemRow> findProblems(UUID candidateId) {
		/*
		 * 개념명 COALESCE 체인 4종. 순서가 계약이다 — 앞엣것이 있으면 뒤는 보지 않는다.
		 *
		 * ① TEACHES_CANONICAL_NAME       팀 공통 문제. CHECK가 NOT NULL을 강제해 항상 성공한다
		 * ② CURRICULUM_EVIDENCE_TEACHES  개인 커밋 문제의 교안 역참조 근거(S-06)
		 * ③ PROBLEM_TITLE                폴백
		 * ④ UNAVAILABLE                  NOT_GENERATED — title까지 CHECK로 NULL이라 이름이 없다
		 *
		 * 소스를 함께 내보내는 이유: TEACHES_CANONICAL_NAME 외에는 검증된 개념명이 아니라
		 * AI가 단정적으로 서술하면 안 된다. 그 판단 재료를 AI에게 넘긴다.
		 */
		return jdbcTemplate.query("""
				SELECT DISTINCT
				       ap.problem_id,
				       ap.problem_no,
				       COALESCE(t1.canonical_name, t2.canonical_name, ap.title) AS concept_name,
				       CASE WHEN t1.canonical_name IS NOT NULL THEN 'TEACHES_CANONICAL_NAME'
				            WHEN t2.canonical_name IS NOT NULL THEN 'CURRICULUM_EVIDENCE_TEACHES'
				            WHEN ap.title          IS NOT NULL THEN 'PROBLEM_TITLE'
				            ELSE 'UNAVAILABLE' END AS concept_name_source,
				       ap.problem_scope,
				       ap.generation_status,
				       ap.not_generated_reason_code,
				       ap.best_success_stage,
				       ap.code_language,
				       ap.source_path,
				       ap.source_line_start,
				       ap.source_line_end,
				       ap.source_snippet_key,
				       ap.code_snippet_hash
				""" + ATTEMPT_FROM + """
				JOIN assessment_session s
				       ON s.attempt_id = ma.attempt_id
				JOIN problem_stage ps0
				       ON ps0.session_id = s.session_id
				JOIN assessment_problem ap
				       ON ap.problem_id = ps0.problem_id
				LEFT JOIN project_verification_concept pvc
				       ON pvc.project_concept_id = ap.project_verification_concept_id
				LEFT JOIN teaches t1
				       ON t1.teaches_id = pvc.teaches_id
				LEFT JOIN LATERAL (
				    SELECT tx.canonical_name
				    FROM assessment_problem_reference apr
				    JOIN teaches tx ON tx.teaches_id = apr.teaches_id
				    WHERE apr.problem_id     = ap.problem_id
				      AND apr.reference_type = 'CURRICULUM_EVIDENCE'
				    ORDER BY apr.display_order
				    LIMIT 1
				) t2 ON TRUE
				WHERE ic.candidate_id = ?
				ORDER BY ap.problem_no
				""",
				(rs, rowNum) -> new ProblemRow(
						rs.getObject("problem_id", UUID.class),
						rs.getInt("problem_no"),
						rs.getString("concept_name"),
						rs.getString("concept_name_source"),
						rs.getString("problem_scope"),
						rs.getString("generation_status"),
						rs.getString("not_generated_reason_code"),
						rs.getString("best_success_stage"),
						rs.getString("code_language"),
						rs.getString("source_path"),
						(Integer) rs.getObject("source_line_start"),
						(Integer) rs.getObject("source_line_end"),
						rs.getString("source_snippet_key"),
						rs.getString("code_snippet_hash")),
				candidateId);
	}

	@Override
	public List<StageRow> findStages(UUID candidateId) {
		return jdbcTemplate.query("""
				SELECT ps.problem_stage_id, ps.problem_id, ps.axis_code, ps.status,
				       ps.question_text, ps.question_answer_text, ps.question_score, ps.question_passed,
				       ps.first_hint_text, ps.first_hint_answer_text, ps.first_hint_score, ps.first_hint_passed,
				       ps.second_hint_text, ps.second_hint_answer_text, ps.second_hint_score, ps.second_hint_passed,
				       ps.is_flagged
				""" + ATTEMPT_FROM + """
				JOIN assessment_session s
				       ON s.attempt_id = ma.attempt_id
				JOIN problem_stage ps
				       ON ps.session_id = s.session_id
				WHERE ic.candidate_id = ?
				ORDER BY ps.problem_id, ps.question_sequence_no
				""", this::mapStage, candidateId);
	}

	@Override
	public List<PriorInterviewRow> findPriorInterviews(UUID traineeUserId, UUID currentInterviewId) {
		/*
		 * askedQuestions는 그 면담의 확정 브리프가 실제로 실었던 질문 원문이다 —
		 * AI가 같은 질문을 반복하지 않고 후속 질문으로 발전시키는 재료다.
		 * 활동은 별도 조회 없이 배열로 접어 온다(면담당 몇 건이라 행 곱셈이 문제되지 않는다).
		 */
		return jdbcTemplate.query("""
				SELECT i.interview_id,
				       i.completed_at,
				       i.result_summary,
				       COALESCE(q.questions, ARRAY[]::text[]) AS asked_questions
				FROM interview i
				LEFT JOIN LATERAL (
				    SELECT ARRAY_AGG(bi.question_text ORDER BY bi.suggested_order) AS questions
				    FROM interview_brief b
				    JOIN interview_brief_item bi ON bi.brief_id = b.brief_id
				    WHERE b.interview_id = i.interview_id
				      AND b.status       = 'CONFIRMED'
				) q ON TRUE
				WHERE i.target_user_id = ?
				  AND i.interview_id  <> ?
				  AND i.status         = 'COMPLETED'
				ORDER BY i.completed_at DESC
				""",
				(rs, rowNum) -> new PriorInterviewRow(
						rs.getObject("interview_id", UUID.class),
						toInstant(rs.getTimestamp("completed_at")),
						rs.getString("result_summary"),
						findActivities(rs.getObject("interview_id", UUID.class)),
						JdbcInterviewListRepository.toStringList(rs.getArray("asked_questions"))),
				traineeUserId, currentInterviewId);
	}

	private List<InterviewBriefAiRequest.PriorInterviewActivity> findActivities(UUID interviewId) {
		return jdbcTemplate.query("""
				SELECT content, next_action, occurred_at
				FROM interview_activity
				WHERE interview_id = ?
				ORDER BY occurred_at
				""",
				(rs, rowNum) -> new InterviewBriefAiRequest.PriorInterviewActivity(
						rs.getString("content"),
						rs.getString("next_action"),
						toInstant(rs.getTimestamp("occurred_at"))),
				interviewId);
	}

	/**
	 * 관찰 메모. 같은 기관·기수의 담당 교육생 것만, 삭제되지 않은 것만 싣는다.
	 *
	 * <p>작성자를 현재 매니저로 좁히지는 않는다 — 같은 반을 함께 맡는 매니저가 남긴 메모도
	 * 면담 재료다. 반 배정이 유효한지가 판단 기준이고, 그건 {@code manager_assignment} 조인이 한다.
	 */
	@Override
	public List<ObservationNoteRow> findObservationNotes(UUID candidateId, UUID managerUserId) {
		return jdbcTemplate.query("""
				SELECT n.note_id, n.occurred_at, n.content, n.visibility
				FROM interview_candidate ic
				JOIN observation_note n
				       ON n.user_id    = ic.user_id
				      AND n.cohort_id  = ic.cohort_id
				      AND n.org_id     = ic.org_id
				      AND n.deleted_at IS NULL
				JOIN manager_assignment ma
				       ON ma.class_id        = ic.class_id
				      AND ma.manager_user_id = ?
				      AND ma.status          = 'ACTIVE'
				      AND ma.unassigned_at IS NULL
				WHERE ic.candidate_id = ?
				ORDER BY n.occurred_at
				""",
				(rs, rowNum) -> new ObservationNoteRow(
						rs.getObject("note_id", UUID.class),
						toInstant(rs.getTimestamp("occurred_at")),
						rs.getString("content"),
						rs.getString("visibility")),
				managerUserId, candidateId);
	}

	private StageRow mapStage(ResultSet rs, int rowNum) throws SQLException {
		return new StageRow(
				rs.getObject("problem_stage_id", UUID.class),
				rs.getObject("problem_id", UUID.class),
				rs.getString("axis_code"),
				rs.getString("status"),
				rs.getString("question_text"),
				rs.getString("question_answer_text"),
				(Integer) rs.getObject("question_score"),
				(Boolean) rs.getObject("question_passed"),
				rs.getString("first_hint_text"),
				rs.getString("first_hint_answer_text"),
				(Integer) rs.getObject("first_hint_score"),
				(Boolean) rs.getObject("first_hint_passed"),
				rs.getString("second_hint_text"),
				rs.getString("second_hint_answer_text"),
				(Integer) rs.getObject("second_hint_score"),
				(Boolean) rs.getObject("second_hint_passed"),
				rs.getBoolean("is_flagged"));
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
