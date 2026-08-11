package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.CohortDependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 존재 여부 하나만 필요하므로 엔티티를 거치지 않고 {@code EXISTS} 한 줄로 읽는다
 * ({@code JdbcClassroomDependencyRepository}와 같은 방식).
 */
@Repository
@RequiredArgsConstructor
public class JdbcCohortDependencyRepository implements CohortDependencyRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public boolean hasMembers(UUID cohortId) {
		// 이탈자와 초대 이력도 센다 — 수락 전에는 cohort_member가 없지만 명단을 올린 사실은 남는다.
		return exists("""
				SELECT EXISTS (
					SELECT 1 FROM cohort_member WHERE cohort_id = ?
					UNION ALL
					SELECT 1 FROM user_invitation
					WHERE target_cohort_id = ? AND target_role_code = 'TRAINEE'
				)
				""", cohortId, cohortId);
	}

	@Override
	public boolean hasClassrooms(UUID cohortId) {
		return exists(
				"SELECT EXISTS (SELECT 1 FROM class WHERE cohort_id = ? AND deleted_at IS NULL)", cohortId);
	}

	@Override
	public boolean hasProjects(UUID cohortId) {
		return exists(
				"SELECT EXISTS (SELECT 1 FROM project WHERE cohort_id = ? AND deleted_at IS NULL)", cohortId);
	}

	private boolean exists(String sql, Object... arguments) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, arguments));
	}
}
