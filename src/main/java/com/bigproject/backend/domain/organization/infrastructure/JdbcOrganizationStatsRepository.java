package com.bigproject.backend.domain.organization.infrastructure;

import com.bigproject.backend.domain.organization.domain.OrganizationStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcOrganizationStatsRepository implements OrganizationStatsRepository {

	/**
	 * v06에서 role.code CHECK가 ('SUPER_ADMIN','OPERATOR','MANAGER','TRAINEE')로 바뀌어
	 * '총괄 매니저(LEAD_MANAGER)'가 '오퍼레이터(OPERATOR)'로 정리됐다.
	 * {@link JdbcOrganizationOperatorRepository}의 동일 상수와 항상 같은 값이어야 한다.
	 */
	private static final String OPERATOR_ROLE_CODE = "OPERATOR";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Map<UUID, CohortCounts> countCohortsByOrgId(Collection<UUID> organizationIds) {
		if (organizationIds.isEmpty()) {
			return Map.of();
		}
		// cohort.status CHECK: IN ('PLANNED','RUNNING','CLOSED'). 목업은 '진행'(RUNNING)과 '종료'(CLOSED)만 카드에 쓴다.
		String sql = """
				SELECT org_id,
				       COUNT(*) AS total,
				       COUNT(*) FILTER (WHERE status = 'RUNNING') AS running,
				       COUNT(*) FILTER (WHERE status = 'CLOSED') AS closed
				FROM cohort
				WHERE deleted_at IS NULL
					AND org_id IN (%s)
				GROUP BY org_id
				""".formatted(placeholders(organizationIds));

		return jdbcTemplate.query(sql, (ResultSet rs) -> {
			Map<UUID, CohortCounts> result = new HashMap<>();
			while (rs.next()) {
				result.put(
						rs.getObject("org_id", UUID.class),
						new CohortCounts(rs.getInt("total"), rs.getInt("running"), rs.getInt("closed"))
				);
			}
			return result;
		}, organizationIds.toArray());
	}

	@Override
	public Map<UUID, List<OrganizationOperator>> findOperatorsByOrgId(Collection<UUID> organizationIds) {
		if (organizationIds.isEmpty()) {
			return Map.of();
		}
		// 목록의 `오퍼레이터` 열은 이름을 순서대로 이어 붙여 렌더링하므로(`박지현 외 1`) 이름 오름차순으로 고정한다.
		String sql = """
				SELECT u.org_id, u.user_id, u.name, u.email
				FROM app_user u
				JOIN "role" r ON r.role_id = u.role_id
				WHERE u.deleted_at IS NULL
					AND u.status = 'ACTIVE'
					AND r.code = ?
					AND u.org_id IN (%s)
				ORDER BY u.name
				""".formatted(placeholders(organizationIds));

		Object[] args = new Object[organizationIds.size() + 1];
		args[0] = OPERATOR_ROLE_CODE;
		int index = 1;
		for (UUID organizationId : organizationIds) {
			args[index++] = organizationId;
		}

		return jdbcTemplate.query(sql, (ResultSet rs) -> {
			Map<UUID, List<OrganizationOperator>> result = new HashMap<>();
			while (rs.next()) {
				UUID orgId = rs.getObject("org_id", UUID.class);
				result.computeIfAbsent(orgId, key -> new ArrayList<>()).add(new OrganizationOperator(
						rs.getObject("user_id", UUID.class),
						rs.getString("name"),
						rs.getString("email")
				));
			}
			return result;
		}, args);
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

	@Override
	public List<OrganizationCohortSummary> findCohortSummaries(UUID organizationId) {
		// 반 수와 교육생 수는 각각 class / cohort_member를 세는데, 한 쿼리에서 조인하면 카티션 곱으로 부풀기 때문에
		// 상관 서브쿼리로 분리해 센다(기관 상세 1건 조회라 기수 수만큼의 서브쿼리 비용은 감당 가능).
		String sql = """
				SELECT c.cohort_id,
				       c.name,
				       c.status,
				       c.start_date,
				       c.end_date,
				       (SELECT COUNT(*) FROM class cl
				         WHERE cl.cohort_id = c.cohort_id AND cl.deleted_at IS NULL) AS class_count,
				       (SELECT COUNT(*) FROM cohort_member cm
				         WHERE cm.cohort_id = c.cohort_id AND cm.left_at IS NULL) AS trainee_count
				FROM cohort c
				WHERE c.deleted_at IS NULL
					AND c.org_id = ?
				ORDER BY c.start_date DESC
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new OrganizationCohortSummary(
				rs.getObject("cohort_id", UUID.class),
				rs.getString("name"),
				rs.getString("status"),
				rs.getInt("class_count"),
				rs.getInt("trainee_count"),
				toLocalDate(rs.getDate("start_date")),
				toLocalDate(rs.getDate("end_date"))
		), organizationId);
	}

	@Override
	public PlatformOrganizationCounts countOrganizationsByStatus() {
		String sql = """
				SELECT COUNT(*) AS total,
				       COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active,
				       COUNT(*) FILTER (WHERE status = 'SUSPENDED') AS suspended
				FROM organization
				WHERE deleted_at IS NULL
				""";

		return jdbcTemplate.query(sql, (ResultSet rs) -> {
			if (!rs.next()) {
				return new PlatformOrganizationCounts(0, 0, 0);
			}
			return new PlatformOrganizationCounts(rs.getInt("total"), rs.getInt("active"), rs.getInt("suspended"));
		});
	}

	@Override
	public int countAllActiveTrainees() {
		String sql = """
				SELECT COUNT(*) AS cnt
				FROM cohort_member cm
				JOIN app_user u ON u.user_id = cm.user_id
				JOIN "role" r ON r.role_id = u.role_id
				WHERE u.deleted_at IS NULL
					AND u.status = 'ACTIVE'
					AND r.code = 'TRAINEE'
					AND cm.left_at IS NULL
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
		return count == null ? 0 : count;
	}

	@Override
	public Map<UUID, Long> sumLatestStorageBytesByOrgId(Collection<UUID> organizationIds, Instant from, Instant to) {
		if (organizationIds.isEmpty()) {
			return Map.of();
		}
		/*
		 * 주기 스냅샷을 그대로 합산하면 같은 저장량이 수집 횟수만큼 중복 집계된다. 저장량은 시점 값이므로
		 * 기관별로 "가장 최근 측정 세트" 하나만 남긴 뒤 그 안에서 합산한다.
		 *
		 * v06 변경점 3가지가 이 쿼리에 그대로 반영돼 있다.
		 *  1) byte_count → used_bytes, as_of_at → captured_at (컬럼명 변경)
		 *  2) aggregation_status 컬럼 소멸 — 실패한 측정은 행 자체를 남기지 않으므로 성공 필터가 불필요해졌다.
		 *  3) measurement_batch_id 신설 — 카테고리별로 최신 행을 따로 고르면 서로 다른 시점이 섞여 총계가
		 *     어긋나므로, 배치(측정 세트) 단위로 고른다.
		 *
		 * ⚠ storage_category에 ORG_TOTAL이 추가됐다. 총계 행과 세부 카테고리를 함께 SUM하면 저장량이 두 배가 된다.
		 *   그래서 ORG_TOTAL이 있으면 그 값만 쓰고, 없을 때만 세부 카테고리를 합산한다.
		 */
		String sql = """
				WITH latest_batch AS (
					SELECT DISTINCT ON (org_id) org_id, measurement_batch_id
					FROM storage_usage_snapshot
					WHERE captured_at >= ?
						AND captured_at < ?
						AND org_id IN (%s)
					ORDER BY org_id, captured_at DESC
				)
				SELECT s.org_id,
				       COALESCE(
				           SUM(s.used_bytes) FILTER (WHERE s.storage_category = 'ORG_TOTAL'),
				           SUM(s.used_bytes) FILTER (WHERE s.storage_category <> 'ORG_TOTAL'),
				           0
				       ) AS total_bytes
				FROM storage_usage_snapshot s
				JOIN latest_batch b
				  ON b.org_id = s.org_id
				 AND b.measurement_batch_id = s.measurement_batch_id
				GROUP BY s.org_id
				""".formatted(placeholders(organizationIds));

		Object[] args = new Object[organizationIds.size() + 2];
		args[0] = Timestamp.from(from);
		args[1] = Timestamp.from(to);
		int index = 2;
		for (UUID organizationId : organizationIds) {
			args[index++] = organizationId;
		}

		return jdbcTemplate.query(sql, (ResultSet rs) -> {
			Map<UUID, Long> result = new HashMap<>();
			while (rs.next()) {
				result.put(rs.getObject("org_id", UUID.class), rs.getLong("total_bytes"));
			}
			return result;
		}, args);
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

	private LocalDate toLocalDate(Date date) {
		return date == null ? null : date.toLocalDate();
	}

	private String placeholders(Collection<UUID> ids) {
		List<String> marks = ids.stream().map(id -> "?").toList();
		return String.join(", ", marks);
	}
}
