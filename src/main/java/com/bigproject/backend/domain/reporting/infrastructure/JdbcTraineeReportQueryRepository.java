package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TR-04 조회 SQL. v07 리포트 뷰 3종을 읽는다.
 *
 * <p>⚠ <b>{@code trainee_report_round_view}는 리포트가 없는 회차를 못 낸다.</b>
 * 뷰 설명은 "Report가 아직 없거나 생성되지 않는 회차까지 포함"이라고 적혀 있지만 실제
 * 정의는 {@code FROM report rpt JOIN project_assessment_round}라 report 행이 있어야만
 * 나온다. 그래서 여기서는 뷰를 쓰지 않고 <b>{@code project_assessment_round}에서 시작해
 * report를 LEFT JOIN</b>한다 — 그래야 미응시 회차가 목록에 남는다(화면 `NOT_ATTEMPTED`).
 * 뷰가 고쳐지면 이 쿼리를 뷰 조회로 되돌린다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcTraineeReportQueryRepository implements TraineeReportQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<RoundRow> findRounds(UUID userId) {
		// 교육생이 속한 기수의 회차를 전부 세우고(cohort_member) 응시·리포트를 LEFT JOIN한다.
		// INITIAL 응시만 본다 — REVIEW(다시 보기)는 rev 서브쿼리에서 따로 읽는다.
		//
		// 정렬은 `project.sequence_no`가 앞이고 `round_no`가 뒤다. round_no는
		// uq_project_assessment_round_no_active가 (project_id, round_no)라 **프로젝트 안에서만**
		// 유일하고, 정의서가 "MINI_PROJECT는 활성 회차 정확히 1건, round_no=1"을 요구하므로
		// 미니프로젝트만 쓰는 지금은 모든 회차가 1이다. round_no만으로 정렬하면 기수의 회차가
		// 전부 같은 값이라 순서가 사실상 무작위가 된다. 기수 안 운영 순서를 가진 축은
		// project.sequence_no다(정의서: "sequence_no는 기수 내 전체 프로젝트 운영 순서").
		String sql = """
				SELECT r.assessment_round_id,
				       r.round_name,
				       r.round_no,
				       p.name                       AS project_name,
				       rpt.report_id,
				       rs.snapshot_id,
				       rs.completion_status,
				       ma.attempt_id,
				       ma.status                    AS attempt_status,
				       ma.terminal_reason_code,
				       ma.validity_review_status,
				       rpt.trainee_release_status,
				       rpt.trainee_disclosure_scope,
				       r.report_publish_not_before_at,
				       rpt.published_at,
				       (rpt.trainee_release_status = 'RELEASED'
				            AND rs.snapshot_id IS NOT NULL) AS can_view_report,
				       rev.status                   AS review_status,
				       rev.review_due_at,
				       rev.terminal_at              AS review_completed_at
				FROM cohort_member cm
				JOIN project_assessment_round r
				       ON r.cohort_id = cm.cohort_id
				      AND r.deleted_at IS NULL
				JOIN project p
				       ON p.project_id = r.project_id
				LEFT JOIN measurement_attempt ma
				       ON ma.assessment_round_id = r.assessment_round_id
				      AND ma.user_id = cm.user_id
				      AND ma.attempt_type = 'INITIAL'
				LEFT JOIN report rpt
				       ON rpt.assessment_round_id = r.assessment_round_id
				      AND rpt.user_id = cm.user_id
				      AND rpt.lifecycle_status <> 'SUPERSEDED'
				LEFT JOIN report_snapshot rs
				       ON rs.report_id = rpt.report_id
				      AND rs.is_active
				LEFT JOIN LATERAL (
				       SELECT x.status, x.review_due_at, x.terminal_at
				       FROM measurement_attempt x
				       WHERE x.source_attempt_id = ma.attempt_id
				         AND x.attempt_type = 'REVIEW'
				       ORDER BY x.attempt_sequence_no DESC
				       LIMIT 1
				) rev ON TRUE
				WHERE cm.user_id = ?
				  AND cm.left_at IS NULL
				ORDER BY p.sequence_no DESC, r.round_no DESC
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new RoundRow(
				rs.getObject("assessment_round_id", UUID.class),
				rs.getString("round_name"),
				rs.getInt("round_no"),
				rs.getString("project_name"),
				rs.getObject("report_id", UUID.class),
				rs.getObject("snapshot_id", UUID.class),
				rs.getString("completion_status"),
				rs.getObject("attempt_id", UUID.class),
				rs.getString("attempt_status"),
				rs.getString("terminal_reason_code"),
				rs.getString("validity_review_status"),
				rs.getString("trainee_release_status"),
				rs.getString("trainee_disclosure_scope"),
				instant(rs, "report_publish_not_before_at"),
				instant(rs, "published_at"),
				rs.getBoolean("can_view_report"),
				rs.getString("review_status"),
				instant(rs, "review_due_at"),
				instant(rs, "review_completed_at")
		), userId);
	}

	@Override
	public List<ConceptRow> findConcepts(UUID userId) {
		// 개념 단위 판정은 뷰가 이미 만들어 둔 것을 그대로 쓴다 — 공개 범위에 따른
		// can_view_explanation 판정이 뷰 안에 있어서 여기서 다시 쓰면 갈라진다.
		String sql = """
				SELECT report_id,
				       problem_id,
				       concept_display_name,
				       concept_display_order,
				       reach_display_code,
				       result_explanation,
				       answer_excerpt,
				       curriculum_location,
				       review_required,
				       review_before_after_items,
				       can_view_explanation
				FROM trainee_report_problem_view
				WHERE user_id = ?
				ORDER BY report_id, concept_display_order
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new ConceptRow(
				rs.getObject("report_id", UUID.class),
				rs.getObject("problem_id", UUID.class),
				rs.getString("concept_display_name"),
				rs.getInt("concept_display_order"),
				rs.getString("reach_display_code"),
				rs.getString("result_explanation"),
				rs.getString("answer_excerpt"),
				rs.getString("curriculum_location"),
				rs.getBoolean("review_required"),
				rs.getString("review_before_after_items"),
				rs.getBoolean("can_view_explanation")
		), userId);
	}

	@Override
	public List<StageAnswerRow> findStageAnswers(UUID userId) {
		/*
		 * problem_stage 한 행이 질문 1 + 힌트 2 = 최대 3슬롯을 담는다(2026-08-03 스키마 변경).
		 * 화면 qa[]는 슬롯 단위라 UNION ALL로 펼친다.
		 *
		 * 답변이 없는 슬롯(answer_text IS NULL)은 빼고 내보낸다 — "안 물어본 것"을
		 * 빈 답변으로 그리면 학생이 무응답으로 오해한다.
		 *
		 * 공개 범위 FULL 조건은 trainee_report_view.can_view_own_answers와 같은 식이다.
		 */
		String sql = """
				WITH visible AS (
				    SELECT rpt.report_id, rs.snapshot_id
				    FROM report rpt
				    JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
				    WHERE rpt.user_id = ?
				      AND rpt.trainee_release_status = 'RELEASED'
				      AND rpt.trainee_disclosure_scope = 'FULL'
				),
				stages AS (
				    SELECT DISTINCT v.report_id, re.problem_id, ps.problem_stage_id,
				           ps.axis_code, ps.question_text, ps.question_answer_text,
				           ps.first_hint_text, ps.first_hint_answer_text,
				           ps.second_hint_text, ps.second_hint_answer_text
				    FROM visible v
				    JOIN report_evidence re ON re.snapshot_id = v.snapshot_id
				    JOIN problem_stage ps ON ps.problem_stage_id = re.problem_stage_id
				)
				SELECT report_id, problem_id, axis_code, 1 AS slot_order,
				       'QUESTION' AS slot_code, question_text AS question_text,
				       question_answer_text AS answer_text
				FROM stages WHERE question_answer_text IS NOT NULL
				UNION ALL
				SELECT report_id, problem_id, axis_code, 2,
				       'FIRST_HINT', first_hint_text, first_hint_answer_text
				FROM stages WHERE first_hint_answer_text IS NOT NULL
				UNION ALL
				SELECT report_id, problem_id, axis_code, 3,
				       'SECOND_HINT', second_hint_text, second_hint_answer_text
				FROM stages WHERE second_hint_answer_text IS NOT NULL
				ORDER BY report_id, problem_id, axis_code, slot_order
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new StageAnswerRow(
				rs.getObject("report_id", UUID.class),
				rs.getObject("problem_id", UUID.class),
				rs.getString("axis_code"),
				rs.getInt("slot_order"),
				rs.getString("slot_code"),
				rs.getString("question_text"),
				rs.getString("answer_text")
		), userId);
	}

	/** TIMESTAMPTZ → Instant. null 컬럼을 0 epoch로 만들지 않으려면 getTimestamp를 거쳐야 한다. */
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
