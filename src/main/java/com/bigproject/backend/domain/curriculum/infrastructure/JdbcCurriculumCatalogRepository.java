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
	 * 한 행이 교안(material) 하나이고 집계 대부분은 SELECT의 {@code v}가 가리키는 버전 기준이다.
	 * 어느 버전을 {@code v}로 고정할지는 {@link #LATEST_VERSION_PREDICATE}·
	 * {@link #EXPLICIT_VERSION_PREDICATE}가 {@link #CATALOG_FROM} 뒤에 붙어 정한다(2026-08-20,
	 * 44차 R1 — 상세가 옛 버전을 지정할 수 있어야 해서 이 부분을 분리했다).
	 *
	 * <p>목록 조회({@link #findPage})는 항상 {@link #LATEST_VERSION_PREDICATE}만 쓴다 — 목록에
	 * 버전 지정 기능은 없다.
	 */
	private static final String CATALOG_SELECT_LIST = """
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
			       /* 사용 회차는 버전이 아니라 '교안' 기준이다 — 삭제 가드와 같은 모집단을 유지한다. */
			       (SELECT COUNT(DISTINCT pc.project_id) FROM project_curriculum pc
			         JOIN curriculum_version cv ON cv.version_id = pc.curriculum_version_id
			         JOIN project p ON p.project_id = pc.project_id AND p.deleted_at IS NULL
			         WHERE cv.material_id = m.material_id) AS used_project_count,
			       /*
			        * 2026-08-20, 44차 R1 — 위와 달리 이 행의 버전(v) 하나만 쓴 회차 수다.
			        * used_project_count(교안 전체)와 다른 모집단이라 이름도 다르다.
			        */
			       (SELECT COUNT(DISTINCT pc3.project_id) FROM project_curriculum pc3
			         JOIN project p3 ON p3.project_id = pc3.project_id AND p3.deleted_at IS NULL
			         WHERE pc3.curriculum_version_id = v.version_id) AS version_used_project_count
			""";

	/** SELECT의 {@code v}가 아직 어느 버전인지 정해지지 않은 FROM/JOIN — 버전 술어는 호출부가 붙인다. */
	private static final String CATALOG_FROM = """
			FROM curriculum_material m
			JOIN curriculum_version v ON v.material_id = m.material_id
			""";

	/**
	 * {@code v}를 그 교안의 최신 버전으로 고정한다. 목록·2-인자 {@code findOne}이 쓴다.
	 * {@code LATERAL} 대신 상관 서브쿼리를 쓰는 이유는 표준 SQL이라 다른 엔진에서도 그대로 돌기
	 * 때문이다.
	 */
	private static final String LATEST_VERSION_PREDICATE = """
			 AND v.version_no = (SELECT MAX(v2.version_no) FROM curriculum_version v2
			                      WHERE v2.material_id = m.material_id)
			""";

	/**
	 * {@code v}를 지정된 버전 하나로 고정한다(2026-08-20, 44차 R1). 자리표시자가 {@code FROM}보다
	 * 먼저(JOIN 절 안에) 나오므로, 이 술어를 쓰는 SQL을 바인딩할 때는 <b>versionId를 다른 WHERE
	 * 인자보다 먼저</b> 넣어야 한다 — {@link #findOne(UUID, UUID, UUID)}가 그 순서를 지킨다.
	 *
	 * <p>여기서는 그 버전이 이 {@code materialId}의 것인지 확인하지 않는다(존재·소속 검증은 서비스
	 * 층의 {@code resolveVersionId}가 먼저 한다) — 확인 없이 다른 교안의 버전 ID를 넣으면
	 * {@code m.material_id = ?} 조건과 부딪혀 그냥 빈 결과가 된다.
	 */
	private static final String EXPLICIT_VERSION_PREDICATE = " AND v.version_id = ?\n";

	/**
	 * 최신 분석 시도 상태로 거르는 조건. SELECT 절의 서브쿼리와 <b>같은 식이어야 한다</b>.
	 *
	 * <p>25차 R1 — {@code = ?}가 아니라 {@code IN (…)}이다. 화면이 `분석 중` 한 라벨로 묶어 쓰는
	 * {@code PENDING}·{@code RUNNING}을 한 번에 거를 수 있어야 해서다. 자리 표시자 개수는
	 * 값 개수만큼 만들어 붙인다.
	 */
	private static final String LATEST_ANALYSIS_STATUS_PREFIX = """
			AND (SELECT a3.status FROM curriculum_analysis a3
			      WHERE a3.version_id = v.version_id
			      ORDER BY a3.requested_at DESC
			      LIMIT 1) IN (""";

	/**
	 * 한 번도 분석하지 않은 교안만 남기는 조건(13차 R2).
	 *
	 * <p>{@code NOT EXISTS}가 아니라 위와 <b>같은 서브쿼리가 NULL인지</b>로 판정한다.
	 * 목록의 {@code analysisStatus}·헤더의 {@code notAnalyzedCount}가 모두 그 식을 쓰므로,
	 * 다른 식으로 거르면 `분석 전 2`를 눌렀는데 3건이 나오는 일이 생긴다.
	 */
	private static final String NOT_ANALYZED_CONDITION = """
			AND (SELECT a4.status FROM curriculum_analysis a4
			      WHERE a4.version_id = v.version_id
			      ORDER BY a4.requested_at DESC
			      LIMIT 1) IS NULL""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<CurriculumCatalogRow> findPage(CurriculumCatalogCriteria criteria, int limit, long offset) {
		Filter filter = new Filter(criteria);
		List<Object> args = new ArrayList<>(filter.args());
		args.add(limit);
		args.add(offset);

		return jdbcTemplate.query(
				CATALOG_SELECT_LIST + CATALOG_FROM + LATEST_VERSION_PREDICATE
						+ filter.where() + orderBy(criteria.sort()) + " LIMIT ? OFFSET ?",
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
	 * 22차 R7 — 경로의 기수를 검증한다. 지운 기수({@code deleted_at})는 없는 것으로 본다.
	 *
	 * <p>{@code org_id}를 함께 거는 이유는 <b>남의 기관 기수의 존재 여부를 알려 주지 않기</b> 위해서다.
	 * 있는 기수인데 403, 없는 기수면 404로 나뉘면 그 차이로 다른 기관의 기수 ID를 확인할 수 있다.
	 */
	@Override
	public boolean cohortExists(UUID cohortId, UUID orgId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (
					SELECT 1 FROM cohort
					WHERE cohort_id = ? AND org_id = ? AND deleted_at IS NULL
				)
				""", Boolean.class, cohortId, orgId));
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
	public Optional<CurriculumCatalogRow> findOne(UUID orgId, UUID materialId, UUID versionId) {
		String versionPredicate = versionId != null ? EXPLICIT_VERSION_PREDICATE : LATEST_VERSION_PREDICATE;
		String sql = CATALOG_SELECT_LIST + CATALOG_FROM + versionPredicate
				+ " WHERE m.org_id = ? AND m.deleted_at IS NULL AND m.material_id = ?";

		// EXPLICIT_VERSION_PREDICATE의 자리표시자가 JOIN 절 안에 있어 WHERE의 인자들보다 먼저 온다.
		List<Object> args = new ArrayList<>();
		if (versionId != null) {
			args.add(versionId);
		}
		args.add(orgId);
		args.add(materialId);

		List<CurriculumCatalogRow> found = jdbcTemplate.query(
				sql, (ResultSet rs, int rowNum) -> mapRow(rs), args.toArray());
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
			List<CurriculumAnalysisStatus> statuses = criteria.statuses();
			if (statuses != null && !statuses.isEmpty()) {
				where.append(' ').append(LATEST_ANALYSIS_STATUS_PREFIX)
						.append("?, ".repeat(statuses.size() - 1))
						.append("?)");
				statuses.forEach(status -> args.add(status.name()));
			}
			// 상태 필터와 함께 오지 않는다 — 호출부가 400으로 먼저 끊는다(13차 R2).
			if (criteria.notAnalyzedOnly()) {
				where.append(' ').append(NOT_ANALYZED_CONDITION);
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
				rs.getLong("version_used_project_count"),
				toInstant(rs.getTimestamp("uploaded_at")),
				rs.getString("uploaded_by_name"));
	}

	private Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
