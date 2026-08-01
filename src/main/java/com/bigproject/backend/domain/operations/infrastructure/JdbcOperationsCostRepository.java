package com.bigproject.backend.domain.operations.infrastructure;

import com.bigproject.backend.domain.operations.domain.OperationsCostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 기수·반별 AI 비용 집계 (목업 OP-06 ⑤).
 *
 * <p>비용은 <b>단가가 설정된 호출만</b> 더하고, 단가 미설정(UNPRICED) 호출은 건수만 따로 센다.
 * 0으로 더해 버리면 청구액이 실제보다 작아 보인다 — 목업 SA-02·SA-03의 `단가 미설정` 원칙과 같다.
 *
 * <p>기수·반에 비용이 한 건도 없어도 행은 남긴다({@code LEFT JOIN}). 목업은 `7기 $268`처럼 기수 목록 자체를
 * 보여주는 표이므로, 비용이 0인 기수가 목록에서 사라지면 운영자가 "왜 안 보이지"를 먼저 묻게 된다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcOperationsCostRepository implements OperationsCostRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<CohortCost> findCohortCosts(UUID organizationId, UUID cohortId, Instant from, Instant to) {
		// cohortId가 null이면 기관 전체 기수. 같은 파라미터를 두 번 넘겨 (NULL이거나 일치) 조건으로 쓴다.
		String sql = """
				SELECT c.cohort_id,
				       c.name,
				       (SELECT COUNT(*) FROM cohort_member cm
				         WHERE cm.cohort_id = c.cohort_id AND cm.left_at IS NULL) AS trainee_count,
				       COALESCE(SUM(COALESCE(u.actual_cost, u.estimated_cost))
				                FILTER (WHERE u.pricing_status <> 'UNPRICED'), 0) AS cost,
				       COUNT(u.usage_id) FILTER (WHERE u.pricing_status = 'UNPRICED') AS unpriced_calls
				FROM cohort c
				LEFT JOIN ai_usage u
				       ON u.cohort_id = c.cohort_id
				      AND u.occurred_at >= ?
				      AND u.occurred_at < ?
				WHERE c.org_id = ?
				  AND c.deleted_at IS NULL
				  AND (?::uuid IS NULL OR c.cohort_id = ?::uuid)
				GROUP BY c.cohort_id, c.name, c.start_date
				ORDER BY c.start_date DESC
				""";

		return jdbcTemplate.query(
				sql,
				(ResultSet rs, int rowNum) -> new CohortCost(
						rs.getObject("cohort_id", UUID.class),
						rs.getString("name"),
						rs.getInt("trainee_count"),
						cost(rs),
						rs.getLong("unpriced_calls")
				),
				Timestamp.from(from), Timestamp.from(to), organizationId, cohortId, cohortId
		);
	}

	@Override
	public List<ClassCost> findClassCosts(UUID organizationId, UUID cohortId, Instant from, Instant to) {
		/*
		 * 담당 매니저는 v06에서 반 단위 기간형 배정으로 정리됐다(manager_assignment.class_id NOT NULL,
		 * role_scope·cohort_id 제거). 한 반에 공동 담당이 허용되므로 가장 먼저 배정된 1명을 대표로 보여준다.
		 * 반 인원은 배정이 해제되지 않은(class_membership.unassigned_at IS NULL) 행만 센다.
		 */
		String sql = """
				SELECT cl.class_id,
				       cl.name,
				       (SELECT au.name FROM manager_assignment ma
				          JOIN app_user au ON au.user_id = ma.manager_user_id
				         WHERE ma.class_id = cl.class_id AND ma.status = 'ACTIVE'
				         ORDER BY ma.assigned_at
				         LIMIT 1) AS manager_name,
				       (SELECT COUNT(*) FROM class_membership cm
				         WHERE cm.class_id = cl.class_id AND cm.unassigned_at IS NULL) AS trainee_count,
				       COALESCE(SUM(COALESCE(u.actual_cost, u.estimated_cost))
				                FILTER (WHERE u.pricing_status <> 'UNPRICED'), 0) AS cost,
				       COUNT(u.usage_id) FILTER (WHERE u.pricing_status = 'UNPRICED') AS unpriced_calls
				FROM class cl
				LEFT JOIN ai_usage u
				       ON u.class_id = cl.class_id
				      AND u.occurred_at >= ?
				      AND u.occurred_at < ?
				WHERE cl.org_id = ?
				  AND cl.cohort_id = ?
				  AND cl.deleted_at IS NULL
				GROUP BY cl.class_id, cl.name
				ORDER BY cl.name
				""";

		return jdbcTemplate.query(
				sql,
				(ResultSet rs, int rowNum) -> new ClassCost(
						rs.getObject("class_id", UUID.class),
						rs.getString("name"),
						rs.getString("manager_name"),
						rs.getInt("trainee_count"),
						cost(rs),
						rs.getLong("unpriced_calls")
				),
				Timestamp.from(from), Timestamp.from(to), organizationId, cohortId
		);
	}

	private BigDecimal cost(ResultSet rs) throws SQLException {
		BigDecimal value = rs.getBigDecimal("cost");
		return value == null ? BigDecimal.ZERO : value;
	}
}
