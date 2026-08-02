package com.bigproject.backend.domain.organization.infrastructure;

import com.bigproject.backend.domain.organization.domain.OrganizationStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcOrganizationStatsRepository implements OrganizationStatsRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Map<UUID, Integer> countActiveCohortsByOrgId(Collection<UUID> organizationIds) {
		if (organizationIds.isEmpty()) {
			return Map.of();
		}
		String sql = """
				SELECT org_id, COUNT(*) AS cnt
				FROM cohort
				WHERE deleted_at IS NULL
					AND org_id IN (%s)
				GROUP BY org_id
				""".formatted(placeholders(organizationIds));
		return countsByOrgId(sql, organizationIds);
	}

	@Override
	public Map<UUID, Integer> countActiveManagersByOrgId(Collection<UUID> organizationIds) {
		if (organizationIds.isEmpty()) {
			return Map.of();
		}
		// OPERATOR/MANAGER 둘 다 "매니저"로 집계한다(member 도메인의 findManagers 기본 필터와 동일한 기준).
		String sql = """
				SELECT u.org_id, COUNT(*) AS cnt
				FROM app_user u
				JOIN "role" r ON r.role_id = u.role_id
				WHERE u.deleted_at IS NULL
					AND u.status = 'ACTIVE'
					AND r.code IN ('OPERATOR', 'MANAGER')
					AND u.org_id IN (%s)
				GROUP BY u.org_id
				""".formatted(placeholders(organizationIds));
		return countsByOrgId(sql, organizationIds);
	}

	@Override
	public Map<UUID, Integer> countActiveTraineesByOrgId(Collection<UUID> organizationIds) {
		if (organizationIds.isEmpty()) {
			return Map.of();
		}
		// cohort_member는 "기수 소속 교육생" 전용 테이블이지만, 다른 도메인 쿼리와 동일하게 r.code = 'TRAINEE'로 한 번 더 방어한다.
		// left_at이 채워진(기수를 이미 나간) 교육생은 "현재" 소속 인원이 아니므로 제외한다.
		String sql = """
				SELECT cm.org_id, COUNT(*) AS cnt
				FROM cohort_member cm
				JOIN app_user u ON u.user_id = cm.user_id
				JOIN "role" r ON r.role_id = u.role_id
				WHERE u.deleted_at IS NULL
					AND u.status = 'ACTIVE'
					AND r.code = 'TRAINEE'
					AND cm.left_at IS NULL
					AND cm.org_id IN (%s)
				GROUP BY cm.org_id
				""".formatted(placeholders(organizationIds));
		return countsByOrgId(sql, organizationIds);
	}

	private Map<UUID, Integer> countsByOrgId(String sql, Collection<UUID> organizationIds) {
		return jdbcTemplate.query(sql, (ResultSet rs) -> {
			Map<UUID, Integer> result = new HashMap<>();
			while (rs.next()) {
				result.put(rs.getObject("org_id", UUID.class), (int) rs.getLong("cnt"));
			}
			return result;
		}, organizationIds.toArray());
	}

	private String placeholders(Collection<UUID> ids) {
		List<String> marks = ids.stream().map(id -> "?").toList();
		return String.join(", ", marks);
	}
}
