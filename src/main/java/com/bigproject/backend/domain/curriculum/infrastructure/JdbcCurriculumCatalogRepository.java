package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogSort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcCurriculumCatalogRepository implements CurriculumCatalogRepository {

	/**
	 * 한 행이 교안(material) 하나이고 값은 <b>최신 버전</b> 기준이다.
	 *
	 * <p>최신 버전을 고르는 데 {@code LATERAL} 대신 상관 서브쿼리
	 * ({@code v.version_no = (SELECT MAX(...))})를 쓴다 — 표준 SQL이라 다른 엔진에서도 그대로 돈다.
	 *
	 * <p>집계 넷을 조인이 아니라 상관 서브쿼리로 둔 이유는, 조인하면 교안 한 건이 섹션·매핑·연결 수만큼
	 * 중복 행으로 늘어나 LIMIT/OFFSET이 행 곱을 먼저 만든 뒤 자르는 꼴이 되기 때문이다
	 * ({@code JdbcManagerRosterRepository}와 같은 이유).
	 */
	private static final String CATALOG_SELECT = """
			SELECT m.material_id,
			       m.title,
			       v.version_id,
			       v.version_no,
			       v.original_file_name,
			       v.page_count,
			       v.created_at AS uploaded_at,
			       (SELECT u.name FROM app_user u WHERE u.user_id = v.created_by) AS uploaded_by_name,
			       /*
			        * 가장 최근 분석 '시도'의 상태다. 성공한 분석이 아니라 시도를 보는 이유는,
			        * 재분석을 걸어 두고 진행 중인지 실패했는지가 화면이 폴링할 대상이기 때문이다.
			        * 한 번도 분석하지 않았으면 NULL이다.
			        */
			       (SELECT a.status FROM curriculum_analysis a
			         WHERE a.version_id = v.version_id
			         ORDER BY a.requested_at DESC
			         LIMIT 1) AS analysis_status,
			       /* 섹션은 가장 최근 '성공' 분석이 만든 것만 센다 — 실패한 시도는 섹션을 만들지 않는다. */
			       (SELECT COUNT(*) FROM curriculum_section s
			         WHERE s.source_analysis_id = (
			               SELECT a2.analysis_id FROM curriculum_analysis a2
			                WHERE a2.version_id = v.version_id AND a2.status = 'SUCCEEDED'
			                ORDER BY a2.completed_at DESC
			                LIMIT 1)) AS section_count,
			       (SELECT COUNT(*) FROM curriculum_teaches_mapping tm
			         WHERE tm.version_id = v.version_id
			           AND tm.mapping_status = 'ACTIVE') AS concept_count,
			       /* 사용 회차는 버전이 아니라 '교안' 기준이다 — 화면이 교안을 파일 하나로 다룬다. */
			       (SELECT COUNT(DISTINCT pc.project_id) FROM project_curriculum pc
			         JOIN curriculum_version cv ON cv.version_id = pc.curriculum_version_id
			         JOIN project p ON p.project_id = pc.project_id AND p.deleted_at IS NULL
			         WHERE cv.material_id = m.material_id) AS used_project_count
			FROM curriculum_material m
			JOIN curriculum_version v ON v.material_id = m.material_id
			 AND v.version_no = (SELECT MAX(v2.version_no) FROM curriculum_version v2
			                      WHERE v2.material_id = m.material_id)
			""";

	/** 최신 분석 시도 상태로 거르는 조건. SELECT 절의 서브쿼리와 <b>같은 식이어야 한다</b>. */
	private static final String LATEST_ANALYSIS_STATUS_CONDITION = """
			AND (SELECT a3.status FROM curriculum_analysis a3
			      WHERE a3.version_id = v.version_id
			      ORDER BY a3.requested_at DESC
			      LIMIT 1) = ?""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<CurriculumCatalogRow> findPage(CurriculumCatalogCriteria criteria, int limit, long offset) {
		Filter filter = new Filter(criteria);
		List<Object> args = new ArrayList<>(filter.args());
		args.add(limit);
		args.add(offset);

		return jdbcTemplate.query(
				CATALOG_SELECT + filter.where() + orderBy(criteria.sort()) + " LIMIT ? OFFSET ?",
				(ResultSet rs, int rowNum) -> mapRow(rs),
				args.toArray());
	}

	@Override
	public long count(CurriculumCatalogCriteria criteria) {
		Filter filter = new Filter(criteria);
		String sql = """
				SELECT COUNT(*)
				FROM curriculum_material m
				JOIN curriculum_version v ON v.material_id = m.material_id
				 AND v.version_no = (SELECT MAX(v2.version_no) FROM curriculum_version v2
				                      WHERE v2.material_id = m.material_id)
				""" + filter.where();
		Long total = jdbcTemplate.queryForObject(sql, Long.class, filter.args().toArray());
		return total == null ? 0 : total;
	}

	/**
	 * 11차 R7. 목록과 같은 모집단(삭제되지 않은 이 기관의 교안 = 최신 버전 한 행)을 쓰되
	 * 검색·상태 필터는 걸지 않는다. 한 번도 분석하지 않은 교안은 상태가 NULL이라 결과에서 빠진다.
	 */
	@Override
	public Map<CurriculumAnalysisStatus, Long> countByAnalysisStatus(UUID orgId) {
		String sql = """
				SELECT (SELECT a.status FROM curriculum_analysis a
				         WHERE a.version_id = v.version_id
				         ORDER BY a.requested_at DESC
				         LIMIT 1) AS analysis_status,
				       COUNT(*) AS material_count
				FROM curriculum_material m
				JOIN curriculum_version v ON v.material_id = m.material_id
				 AND v.version_no = (SELECT MAX(v2.version_no) FROM curriculum_version v2
				                      WHERE v2.material_id = m.material_id)
				WHERE m.org_id = ? AND m.deleted_at IS NULL
				GROUP BY 1
				""";

		Map<CurriculumAnalysisStatus, Long> counts = new LinkedHashMap<>();
		jdbcTemplate.query(sql, (ResultSet rs) -> {
			String status = rs.getString("analysis_status");
			if (status != null) {
				counts.put(CurriculumAnalysisStatus.valueOf(status), rs.getLong("material_count"));
			}
		}, orgId);
		return counts;
	}

	@Override
	public Optional<CurriculumCatalogRow> findOne(UUID orgId, UUID materialId) {
		List<CurriculumCatalogRow> found = jdbcTemplate.query(
				CATALOG_SELECT + " WHERE m.org_id = ? AND m.deleted_at IS NULL AND m.material_id = ?",
				(ResultSet rs, int rowNum) -> mapRow(rs),
				orgId, materialId);
		return found.stream().findFirst();
	}

	/** WHERE 절과 인자를 한 자리에서 같이 쌓는다 — 목록 쿼리와 COUNT 쿼리의 조건이 어긋날 수 없다. */
	private static final class Filter {

		private final StringBuilder where = new StringBuilder(" WHERE m.org_id = ? AND m.deleted_at IS NULL");
		private final List<Object> args = new ArrayList<>();

		private Filter(CurriculumCatalogCriteria criteria) {
			args.add(criteria.orgId());

			if (criteria.query() != null && !criteria.query().isBlank()) {
				where.append(" AND (v.original_file_name ILIKE ? OR m.title ILIKE ?)");
				String like = "%" + criteria.query().trim() + "%";
				args.add(like);
				args.add(like);
			}
			if (criteria.status() != null) {
				where.append(' ').append(LATEST_ANALYSIS_STATUS_CONDITION);
				args.add(criteria.status().name());
			}
		}

		private String where() {
			return where.toString();
		}

		private List<Object> args() {
			return args;
		}
	}

	private String orderBy(CurriculumCatalogSort sort) {
		if (sort == CurriculumCatalogSort.NAME) {
			return " ORDER BY v.original_file_name ASC, v.created_at DESC";
		}
		if (sort == CurriculumCatalogSort.USAGE) {
			return " ORDER BY used_project_count DESC, v.created_at DESC";
		}
		return " ORDER BY v.created_at DESC";
	}

	private CurriculumCatalogRow mapRow(ResultSet rs) throws SQLException {
		String rawStatus = rs.getString("analysis_status");
		return new CurriculumCatalogRow(
				rs.getObject("material_id", UUID.class),
				rs.getObject("version_id", UUID.class),
				rs.getString("title"),
				rs.getString("original_file_name"),
				(Integer) rs.getObject("version_no"),
				(Integer) rs.getObject("page_count"),
				rawStatus == null ? null : CurriculumAnalysisStatus.valueOf(rawStatus),
				rs.getLong("section_count"),
				rs.getLong("concept_count"),
				rs.getLong("used_project_count"),
				toInstant(rs.getTimestamp("uploaded_at")),
				rs.getString("uploaded_by_name"));
	}

	private Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
