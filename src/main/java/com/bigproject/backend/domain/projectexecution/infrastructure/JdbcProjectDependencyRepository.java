package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ProjectDependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * submission·measurement_attempt는 이 도메인의 엔티티가 아니다. 존재 여부 하나만 필요하므로
 * 엔티티를 새로 만들지 않고 {@code EXISTS} 한 줄로 읽는다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcProjectDependencyRepository implements ProjectDependencyRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public boolean hasSubmissions(UUID projectId) {
		// 제출은 팀 단위 원장이라 team을 거친다. 지워진 팀의 제출도 제출이므로 team.deleted_at은 보지 않는다.
		String sql = """
				SELECT EXISTS (
					SELECT 1 FROM submission s
					JOIN team t ON t.team_id = s.team_id
					WHERE t.project_id = ?
				)
				""";
		return exists(sql, projectId);
	}

	@Override
	public boolean hasAssessmentAttempts(UUID projectId) {
		return exists("SELECT EXISTS (SELECT 1 FROM measurement_attempt WHERE project_id = ?)", projectId);
	}

	/**
	 * 활성(ACTIVE) 세트만 본다. 교체돼 이력으로만 남은 과거 세트는 지금 화면이 가리키는 개념이 아니라,
	 * 그것 때문에 교안을 영영 뗄 수 없게 되면 잘못 붙인 교안을 되돌릴 방법이 사라진다.
	 */
	@Override
	public boolean hasConfirmedConceptsFromCurriculum(UUID projectId, UUID curriculumVersionId) {
		String sql = """
				SELECT EXISTS (
					SELECT 1
					FROM project_verification_concept_set cs
					JOIN project_verification_concept c ON c.concept_set_id = cs.concept_set_id
					JOIN curriculum_teaches_mapping m ON m.mapping_id = c.source_mapping_id
					WHERE cs.project_id = ?
						AND cs.status = 'ACTIVE'
						AND m.version_id = ?
				)
				""";
		return Boolean.TRUE.equals(
				jdbcTemplate.queryForObject(sql, Boolean.class, projectId, curriculumVersionId));
	}

	@Override
	public Map<UUID, String> findCohortNames(Collection<UUID> cohortIds) {
		if (cohortIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> names = new HashMap<>();
		jdbcTemplate.query(
				"SELECT cohort_id, name FROM cohort WHERE cohort_id = ANY (?)",
				statement -> statement.setArray(1, uuidArray(statement.getConnection(), cohortIds)),
				(ResultSet rs) -> {
					names.put(rs.getObject("cohort_id", UUID.class), rs.getString("name"));
				});
		return names;
	}

	/**
	 * {@code assessment_round_attendance}는 (회차 × 사람) 한 줄인 뷰다. 한 회차가 여러 반으로
	 * 나뉘어도 사람 기준으로 세야 하므로 {@code DISTINCT user_id}로 센다.
	 */
	@Override
	public Map<UUID, Integer> countAttendedByProject(Collection<UUID> projectIds) {
		if (projectIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Integer> counts = new HashMap<>();
		jdbcTemplate.query("""
						SELECT a.project_id, COUNT(DISTINCT a.user_id) AS attended
						FROM assessment_round_attendance a
						WHERE a.project_id = ANY (?)
						  AND a.primary_attempt_id IS NOT NULL
						GROUP BY a.project_id
						""",
				statement -> statement.setArray(1, uuidArray(statement.getConnection(), projectIds)),
				(ResultSet rs) -> {
					counts.put(rs.getObject("project_id", UUID.class), rs.getInt("attended"));
				});
		return counts;
	}

	/**
	 * IN 절을 물음표로 펼치지 않고 배열 하나로 넘긴다 — 대상 수가 조회마다 달라지면
	 * 매번 다른 SQL이 되어 실행 계획 캐시가 무의미해진다.
	 */
	private static Array uuidArray(Connection connection, Collection<UUID> values) throws SQLException {
		return connection.createArrayOf("uuid", values.toArray(UUID[]::new));
	}

	private boolean exists(String sql, UUID projectId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, projectId));
	}

	/**
	 * 18차 R5 — 제출 마감 시각 갱신.
	 *
	 * <p>{@code org_id}를 조건에 함께 거는 이유는 다른 기관의 회차를 건드릴 수 없게 하기
	 * 위해서다. 호출부가 이미 프로젝트 소유를 확인하지만, 쓰기 쿼리는 그 확인이 빠져도
	 * 안전해야 한다.
	 *
	 * <p>{@code deleted_at IS NULL}로 살아 있는 회차만 본다 — 지운 회차의 마감을 되살리면
	 * 복구했을 때 운영자가 정한 적 없는 값이 들어간다.
	 */
	@Override
	public int updateSubmissionDueAt(UUID projectId, UUID orgId, Instant submissionDueAt) {
		return jdbcTemplate.update("""
				UPDATE project_assessment_round
				   SET submission_due_at = ?,
				       updated_at = CURRENT_TIMESTAMP
				 WHERE project_id = ?
				   AND org_id = ?
				   AND deleted_at IS NULL
				""", Timestamp.from(submissionDueAt), projectId, orgId);
	}

	/**
	 * 22차 R5·R6 — 프로젝트 생성과 <b>같은 트랜잭션</b>에서 회차 1건을 만든다.
	 *
	 * <p>NOT NULL 컬럼을 전부 채운다. {@code trigger_type='MANUAL'}은 운영자가 화면에서 만든
	 * 회차라는 뜻이고, {@code report_publish_mode}는 CHECK가 {@code ROUND_BATCH} 하나만 허용한다.
	 * {@code is_final=TRUE}는 미니프로젝트가 회차 1건이라 그 하나가 곧 마지막 회차이기 때문이며,
	 * {@code uq_project_assessment_round_final_active}가 프로젝트당 한 건만 허용하므로 안전하다.
	 *
	 * <p>응시 창({@code assessment_open_at}·{@code assessment_due_at})과 리포트 발행 하한은
	 * <b>비워 둔다.</b> 셋 다 코드 분석·응시가 끝나야 정해지는 값이라 생성 시점에 넣을 사실이 없고,
	 * CHECK도 {@code PLANNED}에서는 비어 있는 것을 허용한다.
	 */
	@Override
	public UUID createAssessmentRound(UUID projectId, UUID orgId, UUID cohortId, String roundName,
			Instant submissionDueAt, UUID actorUserId) {
		UUID assessmentRoundId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO project_assessment_round (
					assessment_round_id, project_id, org_id, cohort_id,
					round_no, round_name, trigger_type, submission_due_at,
					report_publish_mode, is_final, status, created_by, updated_by
				) VALUES (?, ?, ?, ?, 1, ?, 'MANUAL', ?, 'ROUND_BATCH', TRUE, 'PLANNED', ?, ?)
				""",
				assessmentRoundId, projectId, orgId, cohortId,
				roundName, Timestamp.from(submissionDueAt), actorUserId, actorUserId);
		return assessmentRoundId;
	}

	/**
	 * 살아 있는 회차만 읽는다. 미니프로젝트는 회차가 1건이라 프로젝트당 한 행이지만, 빅프로젝트가
	 * 열려 회차가 여럿이 되면 <b>가장 이른 마감</b>이 그 프로젝트의 마감이다 — 화면이 「이 회차의
	 * 제출 마감」으로 그리는 값이므로 먼저 닫히는 것을 보여줘야 한다.
	 */
	@Override
	public Map<UUID, RoundSchedule> findRoundSchedules(Collection<UUID> projectIds) {
		if (projectIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, RoundSchedule> schedules = new HashMap<>();
		jdbcTemplate.query("""
						SELECT DISTINCT ON (project_id)
						       project_id, submission_due_at, assessment_open_at,
						       assessment_due_at, report_publish_not_before_at
						FROM project_assessment_round
						WHERE project_id = ANY (?)
						  AND deleted_at IS NULL
						ORDER BY project_id, submission_due_at
						""",
				statement -> statement.setArray(1, uuidArray(statement.getConnection(), projectIds)),
				(ResultSet rs) -> {
					schedules.put(rs.getObject("project_id", UUID.class), new RoundSchedule(
							instant(rs, "submission_due_at"),
							instant(rs, "assessment_open_at"),
							instant(rs, "assessment_due_at"),
							instant(rs, "report_publish_not_before_at")));
				});
		return schedules;
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
