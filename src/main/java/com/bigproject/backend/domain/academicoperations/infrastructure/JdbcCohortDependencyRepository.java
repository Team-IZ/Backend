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
		// 이탈자(left_at IS NOT NULL)도 센다 — 지나간 등록도 지우면 안 되는 사실이다.
		return exists("SELECT EXISTS (SELECT 1 FROM cohort_member WHERE cohort_id = ?)", cohortId);
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

	private boolean exists(String sql, UUID cohortId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, cohortId));
	}
}
