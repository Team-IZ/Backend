package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ProjectDependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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

	private boolean exists(String sql, UUID projectId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, projectId));
	}
}
