package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ProjectDependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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

	@Override
	public List<UUID> findProjectIdsByClassId(UUID classId, UUID orgId) {
		String sql = """
				SELECT DISTINCT t.project_id
				FROM team t
				WHERE t.class_id = ? AND t.org_id = ?
				""";
		List<UUID> projectIds = new ArrayList<>();
		jdbcTemplate.query(sql,
				(ResultSet rs) -> {
					projectIds.add(rs.getObject("project_id", UUID.class));
				},
				classId, orgId);
		return projectIds;
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
}