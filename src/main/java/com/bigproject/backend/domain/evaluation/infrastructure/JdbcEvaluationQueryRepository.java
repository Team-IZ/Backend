package com.bigproject.backend.domain.evaluation.infrastructure;

import com.bigproject.backend.domain.evaluation.domain.EvaluationQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 결과 탭 조회의 네이티브 SQL 구현.
 *
 * <p>담당 반 제한은 조인이 아니라 {@code EXISTS}로 건다 — {@code manager_assignment}는 배정·해제 이력이
 * 쌓이는 표라 조인하면 사람 행이 복제돼 집계가 부풀려진다(제출 현황과 같은 이유).
 */
@Repository
@RequiredArgsConstructor
public class JdbcEvaluationQueryRepository implements EvaluationQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	/** 축 코드를 단계 숫자로. 뷰의 {@code highest_reached_level}과 같은 대응이다. */
	private static final String AXIS_LEVEL = """
			CASE ps.axis_code WHEN 'L4' THEN 4 WHEN 'L3' THEN 3 WHEN 'L2' THEN 2 WHEN 'L1' THEN 1 END
			""";

	private static String managedClassFilter(String classColumn) {
		return """
				\tAND EXISTS (
						SELECT 1
						FROM manager_assignment ma2
						WHERE ma2.class_id = %s
							AND ma2.manager_user_id = ?
							AND ma2.status = 'ACTIVE'
							AND ma2.unassigned_at IS NULL
					)
				""".formatted(classColumn);
	}

	/**
	 * 발행 여부는 회차 단위다 — 리포트 발행 방식이 ROUND_BATCH 하나뿐이라 반·개인별로 갈리지 않는다.
	 * {@code class-progress}의 {@code reportPublished}와 같은 식을 쓴다.
	 */
	@Override
	public Optional<RoundScope> findRound(UUID projectId, int roundNo) {
		return jdbcTemplate.query(
				"""
				SELECT
					r.assessment_round_id,
					p.project_id,
					p.org_id,
					p.cohort_id,
					p.name AS project_name,
					r.round_no,
					r.round_name,
					EXISTS (
						SELECT 1 FROM report rpt
						WHERE rpt.assessment_round_id = r.assessment_round_id
							AND rpt.lifecycle_status = 'ACTIVE'
							AND rpt.published_at IS NOT NULL
					) AS report_published,
					(
						SELECT MAX(rpt.published_at) FROM report rpt
						WHERE rpt.assessment_round_id = r.assessment_round_id
							AND rpt.lifecycle_status = 'ACTIVE'
					) AS published_at
				FROM project_assessment_round r
				JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
				WHERE r.project_id = ?
					AND r.round_no = ?
					AND r.deleted_at IS NULL
				""",
				(rs, rowNum) -> new RoundScope(
						rs.getObject("assessment_round_id", UUID.class),
						rs.getObject("project_id", UUID.class),
						rs.getObject("org_id", UUID.class),
						rs.getObject("cohort_id", UUID.class),
						rs.getString("project_name"),
						rs.getInt("round_no"),
						rs.getString("round_name"),
						rs.getBoolean("report_published"),
						instant(rs, "published_at")
				),
				projectId,
				roundNo
		).stream().findFirst();
	}

	@Override
	public boolean isClassManagedBy(UUID managerUserId, UUID classId, UUID cohortId) {
		Boolean managed = jdbcTemplate.queryForObject(
				"""
				SELECT EXISTS (
					SELECT 1
					FROM manager_assignment ma
					JOIN "class" c ON c.class_id = ma.class_id AND c.deleted_at IS NULL
					WHERE ma.manager_user_id = ?
						AND ma.class_id = ?
						AND c.cohort_id = ?
						AND ma.status = 'ACTIVE'
						AND ma.unassigned_at IS NULL
				)
				""",
				Boolean.class,
				managerUserId,
				classId,
				cohortId
		);
		return Boolean.TRUE.equals(managed);
	}

	@Override
	public List<TraineeRow> findTrainees(
			UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId) {
		StringBuilder sql = new StringBuilder("""
				SELECT
					a.user_id,
					u.name AS user_name,
					a.class_id,
					c.name AS class_name,
					a.completion_status,
					a.primary_terminal_reason_code,
					att.validity_review_status
				FROM assessment_round_attendance a
				JOIN app_user u ON u.user_id = a.user_id
				JOIN "class" c ON c.class_id = a.class_id AND c.deleted_at IS NULL
				LEFT JOIN measurement_attempt att ON att.attempt_id = a.primary_attempt_id
				WHERE a.assessment_round_id = ?
					AND a.org_id = ?
				""");
		List<Object> args = new ArrayList<>(List.of(assessmentRoundId, organizationId));
		sql.append(managedClassFilter("a.class_id"));
		args.add(managerUserId);
		if (classId != null) {
			sql.append("\tAND a.class_id = ?\n");
			args.add(classId);
		}
		sql.append("ORDER BY u.name, a.user_id");

		return jdbcTemplate.query(
				sql.toString(),
				(rs, rowNum) -> new TraineeRow(
						rs.getObject("user_id", UUID.class),
						rs.getString("user_name"),
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						rs.getString("completion_status"),
						rs.getString("primary_terminal_reason_code"),
						rs.getString("validity_review_status")
				),
				args.toArray()
		);
	}

	/**
	 * 문제는 팀 공용({@code TEAM_SHARED_PROBLEM})이라 {@code code_analysis_id}로 붙는다.
	 * 개인 커밋 문제({@code INDIVIDUAL_OWN_COMMIT})는 검증 개념이 아예 없어(제약으로 NULL 강제)
	 * 개념별 표를 만들 수 없고, 빅프로젝트가 제품에서 빠지면서 쓰이지도 않는다.
	 *
	 * <p>도달 단계는 통과한 축의 최댓값이다. 한 축이라도 미달하면 그 문제가 끝나 뒤 축은
	 * {@code NOT_REACHED}로 남으므로, 최댓값이 곧 연속 통과 수다.
	 */
	@Override
	public List<ConceptResultRow> findConceptResults(
			UUID assessmentRoundId, UUID organizationId, UUID managerUserId, UUID classId, UUID userId) {
		StringBuilder sql = new StringBuilder("""
				SELECT
					att.user_id,
					pvc.project_concept_id,
					COALESCE(tch.canonical_name, ap.title) AS concept_name,
					COALESCE(pvc.sequence_no, ap.problem_no) AS display_order,
					ap.problem_id,
					(ap.generation_status = 'GENERATED') AS generated,
					COALESCE(lvl.reach_level, 0) AS reach_level
				FROM measurement_attempt att
				JOIN assessment_round_attendance a
					ON a.assessment_round_id = att.assessment_round_id AND a.user_id = att.user_id
				JOIN assessment_problem ap
					ON ap.code_analysis_id = att.code_analysis_id
					AND ap.problem_scope = 'TEAM_SHARED_PROBLEM'
				JOIN project_verification_concept pvc
					ON pvc.project_concept_id = ap.project_verification_concept_id
				LEFT JOIN teaches tch ON tch.teaches_id = pvc.teaches_id
				LEFT JOIN assessment_session s ON s.attempt_id = att.attempt_id
				LEFT JOIN LATERAL (
					SELECT MAX(%s) AS reach_level
					FROM problem_stage ps
					WHERE ps.session_id = s.session_id
						AND ps.problem_id = ap.problem_id
						AND ps.status = 'PASSED'
				) lvl ON TRUE
				WHERE att.assessment_round_id = ?
					AND att.org_id = ?
					AND att.attempt_type = 'INITIAL'
				""".formatted(AXIS_LEVEL.trim()));
		List<Object> args = new ArrayList<>(List.of(assessmentRoundId, organizationId));
		sql.append(managedClassFilter("a.class_id"));
		args.add(managerUserId);
		if (classId != null) {
			sql.append("\tAND a.class_id = ?\n");
			args.add(classId);
		}
		if (userId != null) {
			sql.append("\tAND att.user_id = ?\n");
			args.add(userId);
		}
		sql.append("ORDER BY att.user_id, display_order, ap.problem_id");

		return jdbcTemplate.query(
				sql.toString(),
				(rs, rowNum) -> new ConceptResultRow(
						rs.getObject("user_id", UUID.class),
						rs.getObject("project_concept_id", UUID.class),
						rs.getString("concept_name"),
						rs.getInt("display_order"),
						rs.getObject("problem_id", UUID.class),
						rs.getBoolean("generated"),
						rs.getInt("reach_level")
				),
				args.toArray()
		);
	}

	/**
	 * 채점 근거는 활성 스냅샷의 {@code RESULT_EXPLANATION}에서 읽는다.
	 *
	 * <p>처음에 {@code ANSWER_EXCERPT}를 봤다가 실데이터에서 한 건도 못 찾았다. 근거 뷰의
	 * {@code review_target_status}가 그 카테고리를 쓰길래 따라간 것이었는데, 그쪽은 <b>다시 보기 대상
	 * 표시용 판정</b>이지 근거 텍스트의 출처가 아니다. 의미로도 이쪽이 맞다 — 화면이 펼쳐 읽는 것은
	 * 판정 설명이지 학생 답변 인용({@code quote_excerpt})이 아니다.
	 *
	 * <p>{@code problem_stage_id}로 붙인다. {@code problem_id}로 맞추면 한 문제의 근거가 그 문제의 모든
	 * 축에 같은 값으로 붙는다.
	 *
	 * <p>리포트를 만들기 전에는 이 행이 없으므로 {@code note}가 null이고, 화면의 '채점 근거' 펼침은
	 * 비어 있게 된다 — 발행 전에는 도달 단계와 통과 여부까지만 보여줄 수 있다는 뜻이다.
	 */
	@Override
	public List<StageRow> findStages(UUID assessmentRoundId, UUID userId) {
		return jdbcTemplate.query(
				"""
				SELECT
					ps.problem_id,
					ps.axis_code,
					(ps.status = 'PASSED') AS passed,
					CASE
						WHEN ps.second_hint_answer_text IS NOT NULL THEN 2
						WHEN ps.first_hint_answer_text IS NOT NULL THEN 1
						ELSE 0
					END AS help_count,
					COALESCE(ps.second_hint_score, ps.first_hint_score, ps.question_score) AS score,
					ev.evidence_summary AS note
				FROM measurement_attempt att
				JOIN assessment_session s ON s.attempt_id = att.attempt_id
				JOIN problem_stage ps ON ps.session_id = s.session_id
				LEFT JOIN LATERAL (
					SELECT re.evidence_summary
					FROM report_evidence re
					JOIN report_snapshot rs ON rs.snapshot_id = re.snapshot_id AND rs.is_active
					JOIN report rpt ON rpt.report_id = rs.report_id
						AND rpt.user_id = att.user_id
						AND rpt.assessment_round_id = att.assessment_round_id
						AND rpt.lifecycle_status = 'ACTIVE'
					WHERE re.problem_stage_id = ps.problem_stage_id
						AND re.evidence_category = 'RESULT_EXPLANATION'
					ORDER BY re.display_order NULLS LAST
					LIMIT 1
				) ev ON TRUE
				WHERE att.assessment_round_id = ?
					AND att.user_id = ?
					AND att.attempt_type = 'INITIAL'
					AND ps.status IN ('PASSED', 'NOT_PASSED')
				ORDER BY ps.problem_id, ps.axis_code
				""",
				(rs, rowNum) -> new StageRow(
						rs.getObject("problem_id", UUID.class),
						rs.getString("axis_code"),
						rs.getBoolean("passed"),
						rs.getInt("help_count"),
						integer(rs, "score"),
						rs.getString("note")
				),
				assessmentRoundId,
				userId
		);
	}

	/** TIMESTAMPTZ → Instant. null 컬럼을 0 epoch로 만들지 않으려면 getTimestamp를 거쳐야 한다. */
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	/** 점수는 아직 안 매겨졌으면 null이다 — getInt는 0으로 만들어 '0점'과 구분이 사라진다. */
	private static Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}
}
