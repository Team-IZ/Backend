package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.TraineeRosterSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcTraineeRosterRepository implements TraineeRosterRepository {

	private final JdbcTemplate jdbcTemplate;

	private static final String ROSTER_SELECT = """
			SELECT cm.user_id AS trainee_id, u.name, u.email, u.status AS account_status,
			       c.class_id AS classroom_id, c.name AS class_name,
			       cm.joined_at, cm.left_at
			FROM cohort_member cm
			JOIN app_user u ON u.user_id = cm.user_id AND u.deleted_at IS NULL
			LEFT JOIN class_membership csm ON csm.cohort_member_id = cm.cohort_member_id AND csm.unassigned_at IS NULL
			LEFT JOIN class c ON c.class_id = csm.class_id
			""";

	@Override
	public Optional<CohortScope> findCohortScope(UUID cohortId) {
		String sql = "SELECT cohort_id, org_id FROM cohort WHERE cohort_id = ? AND deleted_at IS NULL";
		return jdbcTemplate.query(sql,
						(ResultSet rs, int rowNum) -> new CohortScope(
								rs.getObject("cohort_id", UUID.class), rs.getObject("org_id", UUID.class)),
						cohortId)
				.stream().findFirst();
	}

	@Override
	public Page<RosterRow> findRoster(RosterCriteria criteria, Pageable pageable) {
		StringBuilder where = new StringBuilder(" WHERE cm.cohort_id = ? AND cm.org_id = ?");
		List<Object> args = new ArrayList<>(List.of(criteria.cohortId(), criteria.orgId()));

		if (criteria.classroomId() != null) {
			where.append(" AND c.class_id = ?");
			args.add(criteria.classroomId());
		}
		if (criteria.unassignedOnly()) {
			where.append(" AND csm.class_id IS NULL");
		}
		if (criteria.rawAccountStatus() != null) {
			where.append(" AND u.status = ?");
			args.add(criteria.rawAccountStatus());
		}
		if (criteria.query() != null && !criteria.query().isBlank()) {
			where.append(" AND (u.name ILIKE ? OR u.email ILIKE ?)");
			String likeQuery = "%" + criteria.query().trim() + "%";
			args.add(likeQuery);
			args.add(likeQuery);
		}

		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM cohort_member cm "
						+ "JOIN app_user u ON u.user_id = cm.user_id AND u.deleted_at IS NULL "
						+ "LEFT JOIN class_membership csm ON csm.cohort_member_id = cm.cohort_member_id AND csm.unassigned_at IS NULL "
						+ "LEFT JOIN class c ON c.class_id = csm.class_id"
						+ where,
				Long.class, args.toArray());

		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(pageable.getPageSize());
		pageArgs.add(pageable.getOffset());

		List<RosterRow> content = jdbcTemplate.query(
				ROSTER_SELECT + where + orderBy(criteria.sort()) + " LIMIT ? OFFSET ?",
				(ResultSet rs, int rowNum) -> mapRow(rs),
				pageArgs.toArray());

		return new PageImpl<>(content, pageable, total == null ? 0 : total);
	}

	@Override
	public int countUnassigned(UUID cohortId, UUID orgId) {
		String sql = """
				SELECT COUNT(*) FROM cohort_member cm
				WHERE cm.cohort_id = ? AND cm.org_id = ?
				  AND NOT EXISTS (
				      SELECT 1 FROM class_membership csm
				      WHERE csm.cohort_member_id = cm.cohort_member_id AND csm.unassigned_at IS NULL
				  )
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, cohortId, orgId);
		return count == null ? 0 : count;
	}

	/**
	 * {@link #countUnassigned}와 <b>같은 모집단</b>(그 기수의 cohort_member 전체)을 센다.
	 * 두 값이 같은 분모 위에 있어야 화면의 '393명 중 미배정 12'가 성립한다.
	 */
	@Override
	public int countCohortTotal(UUID cohortId, UUID orgId) {
		String sql = """
				SELECT COUNT(*) FROM cohort_member cm
				WHERE cm.cohort_id = ? AND cm.org_id = ?
				""";
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, cohortId, orgId);
		return count == null ? 0 : count;
	}

	@Override
	public Optional<RosterRow> findTrainee(UUID traineeId, UUID cohortId, UUID orgId) {
		String sql = ROSTER_SELECT + " WHERE cm.user_id = ? AND cm.cohort_id = ? AND cm.org_id = ?";
		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> mapRow(rs), traineeId, cohortId, orgId)
				.stream().findFirst();
	}

	/**
	 * {@code JdbcOrganizationOperatorRepository.updateOperatorStatus}와 같은 CHECK 대응 방식이다.
	 * ck_app_user_status_3(INACTIVE면 inactivated_* 세 컬럼이 모두 NOT NULL)을 만족시켜야 하므로
	 * 활성화 방향이든 정지 방향이든 세 컬럼을 한 세트로 채우거나 비운다.
	 */
	@Override
	public int updateStatus(UUID traineeId, String rawStatus, UUID actorUserId, String reasonCode, String reason) {
		boolean inactivating = "INACTIVE".equals(rawStatus);
		String sql = """
				UPDATE app_user
				SET status = ?,
				    inactivated_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
				    inactivated_by = CASE WHEN ? THEN ?::uuid ELSE NULL END,
				    inactivated_reason_code = CASE WHEN ? THEN ? ELSE NULL END,
				    inactivated_reason = CASE WHEN ? THEN ? ELSE NULL END,
				    updated_at = CURRENT_TIMESTAMP,
				    row_version = row_version + 1
				WHERE user_id = ?
				    AND deleted_at IS NULL
				""";
		return jdbcTemplate.update(
				sql,
				rawStatus,
				inactivating,
				inactivating, actorUserId,
				inactivating, reasonCode,
				inactivating, reason,
				traineeId
		);
	}

	private String orderBy(TraineeRosterSort sort) {
		if (sort == TraineeRosterSort.RECENT_ENROLLED) {
			return " ORDER BY cm.joined_at DESC, u.name ASC, u.email ASC";
		}
		return " ORDER BY u.name ASC, u.email ASC";
	}

	private RosterRow mapRow(ResultSet rs) throws SQLException {
		return new RosterRow(
				rs.getObject("trainee_id", UUID.class),
				rs.getString("name"),
				rs.getString("email"),
				rs.getString("account_status"),
				rs.getObject("classroom_id", UUID.class),
				rs.getString("class_name"),
				toOffsetDateTime(rs.getTimestamp("joined_at")),
				toOffsetDateTime(rs.getTimestamp("left_at"))
		);
	}

	private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
	}
}
