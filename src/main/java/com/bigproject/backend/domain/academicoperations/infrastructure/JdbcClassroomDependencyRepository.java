package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.ClassroomDependencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * team·report는 이 도메인의 엔티티가 아니라서 JPA로 물어볼 수 없다. 존재 여부 하나만 필요하므로
 * 엔티티를 새로 만들지 않고 {@code EXISTS} 한 줄로 읽는다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcClassroomDependencyRepository implements ClassroomDependencyRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public boolean hasTeams(UUID classId) {
		// 팀도 소프트 삭제라 살아 있는 것만 센다.
		return exists("SELECT EXISTS (SELECT 1 FROM team WHERE class_id = ? AND deleted_at IS NULL)", classId);
	}

	@Override
	public boolean hasReports(UUID classId) {
		// report에는 소프트 삭제 컬럼이 없다 — 행이 있으면 그대로 발행 이력이다.
		return exists("SELECT EXISTS (SELECT 1 FROM report WHERE class_id = ?)", classId);
	}

	private boolean exists(String sql, UUID classId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, classId));
	}
}
