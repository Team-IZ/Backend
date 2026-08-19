package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.UnaskedConceptRow;
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
		// 🔴 report는 LATERAL … LIMIT 1이다. 평범한 LEFT JOIN이면 회차가 행 수만큼 늘어나 화면 목록에
		// 같은 회차가 두 줄로 보인다(보고된 증상: `미니프로젝트 6차`가 두 번).
		//
		// 유일성을 믿을 수 없는 자리다. uq_report_active_user는
		// (assessment_round_id, user_id, report_type) 유일성을 <b>lifecycle_status='ACTIVE'에서만</b>
		// 건다. 그런데 이 쿼리의 조건은 `<> 'SUPERSEDED'`라 DRAFT까지 포함하므로 두 경로로 겹친다 —
		//   · 같은 회차에 DRAFT 하나와 ACTIVE 하나가 함께 있다(재생성 중이거나 발행 전)
		//   · report_type이 다른 두 건이 있다(CHECKPOINT · TRAINEE_FINAL)
		// 둘 다 인덱스가 막지 않는다. 조건을 ACTIVE로 좁히는 방법도 있지만 그러면 발행 전 회차의
		// 상태가 통째로 사라지므로, 행을 하나로 고르는 쪽을 택했다.
		//
		// cohort_member·measurement_attempt는 평범한 조인으로 둔다 — 각각 uq_cohort_member_cohort_id_user_id와
		// uq_measurement_attempt_initial이 1행을 보장한다. 보장되는 것까지 LATERAL로 감싸면 어디가
		// 실제로 위험한 자리인지 읽는 사람이 알 수 없게 된다.
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
				       rs.sample_count,
				       rs.missing_count,
				       ma.attempt_id,
				       ma.status                    AS attempt_status,
				       ma.terminal_reason_code,
				       ma.validity_review_status,
				       r.submission_due_at,
				       r.report_publish_not_before_at,
				       rpt.published_at,
				       rev.status                   AS review_status,
				       rev.review_due_at,
				       rev.terminal_at              AS review_completed_at
				FROM cohort_member cm
				JOIN project_assessment_round r
				       ON r.cohort_id = cm.cohort_id
				      AND r.deleted_at IS NULL
				JOIN project p
				       ON p.project_id = r.project_id
				-- 1행이 보장된다: uq_measurement_attempt_initial이 (assessment_round_id, user_id)에
				-- attempt_type='INITIAL' 부분 유니크를 건다. 그래서 여기는 평범한 조인으로 둔다.
				LEFT JOIN measurement_attempt ma
				       ON ma.assessment_round_id = r.assessment_round_id
				      AND ma.user_id = cm.user_id
				      AND ma.attempt_type = 'INITIAL'
				LEFT JOIN LATERAL (
				       SELECT x.report_id, x.published_at
				       FROM report x
				       WHERE x.assessment_round_id = r.assessment_round_id
				         AND x.user_id = cm.user_id
				         AND x.lifecycle_status <> 'SUPERSEDED'
				       -- report에는 created_at이 없다. 학생이 실제로 볼 수 있는 것을 먼저 고른다 —
				       -- 종전에는 그 기준이 trainee_release_status='RELEASED'였는데 공개/비공개가
				       -- 폐지되면서 published_at이 그 자리를 그대로 물려받았다(발행이 곧 공개다).
				       -- NULLS LAST가 발행된 행을 앞으로 보내므로 정렬 키 하나로 둘 다 된다.
				       -- report_id는 동률을 끊어 같은 입력에 늘 같은 행이 나오게 하는 마지막 기준이다.
				       ORDER BY x.published_at DESC NULLS LAST,
				                x.report_id DESC
				       LIMIT 1
				) rpt ON TRUE
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
				rs.getInt("sample_count"),
				rs.getInt("missing_count"),
				rs.getObject("attempt_id", UUID.class),
				rs.getString("attempt_status"),
				rs.getString("terminal_reason_code"),
				rs.getString("validity_review_status"),
				instant(rs, "submission_due_at"),
				instant(rs, "report_publish_not_before_at"),
				instant(rs, "published_at"),
				rs.getString("review_status"),
				instant(rs, "review_due_at"),
				instant(rs, "review_completed_at")
		), userId);
	}

	@Override
	public List<ConceptRow> findConcepts(UUID userId) {
		/*
		 * 개념 단위 판정은 뷰가 이미 만들어 둔 것을 그대로 쓴다 — 공개 범위에 따른
		 * can_view_explanation 판정이 뷰 안에 있어서 여기서 다시 쓰면 갈라진다.
		 *
		 * 🔴 도달 단계만은 예외다. 뷰의 reach_display_code 는
		 * COALESCE(assessment_problem.best_success_stage,'L0') 인데,
		 * ck_assessment_problem_best_success_stage_2 가 problem_scope='TEAM_SHARED_PROBLEM' 행의
		 * 그 컬럼을 항상 NULL 로 강제한다. 미니프로젝트는 전부 팀 공유 문제라 어떤 데이터를 넣어도
		 * 전 개념이 L0(0단)으로 나갔다 — 같은 응답의 said 문장·isRetryTarget 과 정면으로 어긋나
		 * "하나도 못 했다면서 무엇을 했다고 설명하는" 리포트가 됐다(20차 R2·R3).
		 *
		 * 그래서 원천을 둘로 잡는다.
		 *
		 *   ① report_evidence.trace_payload->>'reachedLevel' — 발행 시점에 얼린 값이다.
		 *      ReportEvidenceFactory 가 AI reachedStage(없으면 problem_stage 집계)로 적고,
		 *      isRetryTarget(decision_code)도 같은 값에서 갈린다. 리포트는 스냅샷이므로
		 *      발행 당시 값을 쓰는 것이 맞고, 두 필드가 같은 원천을 보게 된다.
		 *   ② 그 키가 없는 행(구 스냅샷·시드)은 problem_stage 에서 다시 센다.
		 *      도달 단계 = MAX(axis_code) FILTER (status='PASSED'), 없으면 0단이다.
		 *      세션·단계는 교육생별로 독립이라 본인 INITIAL 응시의 세션만 탄다.
		 *
		 * 뷰를 고치지 않는 이유는 정의서 변경 + DB 마이그레이션이 함께 필요해 배포 단위가
		 * 달라지기 때문이다. 뷰가 교정되면 ①②를 걷어내고 컬럼 하나로 되돌린다.
		 */
		String sql = """
				SELECT v.report_id,
				       v.problem_id,
				       v.concept_display_name,
				       v.concept_display_order,
				       COALESCE(
				           NULLIF(re.trace_payload->>'reachedLevel', '')::INTEGER,
				           stage.reached_level,
				           0
				       )                                AS reach_level,
				       v.result_explanation,
				       v.answer_excerpt,
				       v.curriculum_location,
				       v.review_required,
				       v.review_before_after_items,
				       v.can_view_explanation
				FROM trainee_report_problem_view v
				LEFT JOIN report_evidence re
				       ON re.snapshot_id       = v.snapshot_id
				      AND re.problem_id        = v.problem_id
				      AND re.evidence_category = 'RESULT_EXPLANATION'
				LEFT JOIN LATERAL (
				       SELECT MAX(SUBSTRING(ps.axis_code FROM 2)::INTEGER) AS reached_level
				       FROM problem_stage ps
				       JOIN assessment_session s
				              ON s.session_id = ps.session_id
				       JOIN measurement_attempt ma
				              ON ma.attempt_id   = s.attempt_id
				             AND ma.user_id      = v.user_id
				             AND ma.attempt_type = 'INITIAL'
				       WHERE ps.problem_id = v.problem_id
				         AND ps.status     = 'PASSED'
				) stage ON TRUE
				WHERE v.user_id = ?
				ORDER BY v.report_id, v.concept_display_order
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new ConceptRow(
				rs.getObject("report_id", UUID.class),
				rs.getObject("problem_id", UUID.class),
				rs.getString("concept_display_name"),
				rs.getInt("concept_display_order"),
				rs.getInt("reach_level"),
				rs.getString("result_explanation"),
				rs.getString("answer_excerpt"),
				rs.getString("curriculum_location"),
				rs.getBoolean("review_required"),
				rs.getString("review_before_after_items"),
				rs.getBoolean("can_view_explanation")
		), userId);
	}

	@Override
	public List<UnaskedConceptRow> findUnaskedConcepts(UUID userId) {
		/*
		 * 뷰를 쓸 수 없다 — trainee_report_problem_view 는 report_evidence 에서 시작하는데
		 * NOT_GENERATED 개념에는 근거 행이 없어 뷰에 나타나지 않는다(그 자리에서 사라지는 것이
		 * 정확히 이 조회가 메우려는 구멍이다).
		 *
		 * 그래서 리포트 → 응시 → 코드분석 → 문제 슬롯 순으로 베이스 테이블을 직접 탄다.
		 * assessment_problem 은 근거를 못 찾은 개념에도 NOT_GENERATED 슬롯을 남기므로
		 * (JdbcAnalysisResultRepository 참고) 여기서 개념 이름을 되찾을 수 있다.
		 *
		 * 발행·공개된 리포트만 본다. 묻지 못했다는 사실도 리포트 본문의 일부라, 공개 범위가
		 * 정해지기 전에 내보내면 PENDING_VISIBILITY 회차에서 개념 카드가 새어 나간다.
		 * 범위는 SUMMARY 로 충분하다 — 개념 이름과 "묻지 못함"까지는 요약에 들어간다.
		 */
		String sql = """
				SELECT rpt.report_id,
				       ap.problem_id,
				       COALESCE(t.canonical_name, ap.title) AS concept_display_name,
				       pvc.sequence_no                      AS concept_display_order,
				       ap.not_generated_reason_code
				FROM report rpt
				JOIN measurement_attempt ma
				       ON ma.assessment_round_id = rpt.assessment_round_id
				      AND ma.user_id             = rpt.user_id
				      AND ma.attempt_type        = 'INITIAL'
				JOIN assessment_problem ap
				       ON ap.code_analysis_id   = ma.code_analysis_id
				      AND ap.generation_status  = 'NOT_GENERATED'
				LEFT JOIN project_verification_concept pvc
				       ON pvc.project_concept_id = ap.project_verification_concept_id
				LEFT JOIN teaches t
				       ON t.teaches_id = pvc.teaches_id
				WHERE rpt.user_id = ?
				  AND rpt.lifecycle_status <> 'SUPERSEDED'
				  AND rpt.trainee_release_status = 'RELEASED'
				  AND rpt.trainee_disclosure_scope IN ('SUMMARY', 'FULL')
				ORDER BY rpt.report_id, pvc.sequence_no, ap.problem_no
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new UnaskedConceptRow(
				rs.getObject("report_id", UUID.class),
				rs.getObject("problem_id", UUID.class),
				rs.getString("concept_display_name"),
				rs.getInt("concept_display_order"),
				rs.getString("not_generated_reason_code")
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
