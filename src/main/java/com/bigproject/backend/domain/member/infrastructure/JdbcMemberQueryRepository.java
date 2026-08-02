package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberQueryRepository;
import com.bigproject.backend.domain.member.domain.MemberSortField;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcMemberQueryRepository implements MemberQueryRepository {
	private final JdbcTemplate jdbcTemplate;

	@Override
	public boolean existsOrganization(UUID organizationId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM organization WHERE org_id = ? AND deleted_at IS NULL",
				Integer.class,
				organizationId
		);
		return count != null && count > 0;
	}

	@Override
	public Optional<CohortScope> findCohortScope(UUID cohortId) {
		return jdbcTemplate.query(
				"SELECT cohort_id, org_id FROM cohort WHERE cohort_id = ? AND deleted_at IS NULL",
				(rs, rowNum) -> new CohortScope(
						rs.getObject("cohort_id", UUID.class),
						rs.getObject("org_id", UUID.class)
				),
				cohortId
		).stream().findFirst();
	}

	@Override
	public boolean classroomBelongsToCohort(UUID classroomId, UUID cohortId, UUID organizationId) {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM "class"
				WHERE class_id = ?
					AND cohort_id = ?
					AND org_id = ?
					AND deleted_at IS NULL
				""",
				Integer.class,
				classroomId,
				cohortId,
				organizationId
		);
		return count != null && count > 0;
	}

	@Override
	public Page<ManagerRow> findManagers(ManagerCriteria criteria) {
		StringBuilder fromAndWhere = new StringBuilder("""
				FROM app_user u
				JOIN "role" r ON r.role_id = u.role_id
				WHERE u.org_id = ?
				""");
		List<Object> parameters = new ArrayList<>();
		parameters.add(criteria.organizationId());
		appendRoleFilter(fromAndWhere, parameters, criteria.role());
		appendAccountStatusFilter(fromAndWhere, parameters, criteria.status());
		appendQueryFilter(fromAndWhere, parameters, criteria.query());

		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) " + fromAndWhere,
				Long.class,
				parameters.toArray()
		);

		String sql = """
				SELECT
					u.user_id,
					u.name,
					u.email,
					r.code AS role_code,
					u.status,
					u.deleted_at,
					u.org_id,
					u.last_login_at
				""" + fromAndWhere + managerOrderBy(criteria) + " LIMIT ? OFFSET ?";
		List<Object> pageParameters = new ArrayList<>(parameters);
		pageParameters.add(criteria.size());
		pageParameters.add((long) criteria.page() * criteria.size());
		List<ManagerRow> content = jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new ManagerRow(
						rs.getObject("user_id", UUID.class),
						rs.getString("name"),
						rs.getString("email"),
						Role.valueOf(rs.getString("role_code")),
						rs.getString("status"),
						rs.getTimestamp("deleted_at") != null,
						rs.getObject("org_id", UUID.class),
						instant(rs, "last_login_at")
				),
				pageParameters.toArray()
		);
		return new Page<>(content, total == null ? 0 : total);
	}

	@Override
	public List<ManagerAssignmentRow> findManagerAssignments(List<UUID> managerIds) {
		if (managerIds.isEmpty()) {
			return List.of();
		}
		String placeholders = String.join(", ", managerIds.stream().map(id -> "?").toList());
		String sql = """
				SELECT
					ma.assignment_id,
					ma.manager_user_id,
					'CLASS' AS role_scope,
					cl.cohort_id,
					c.name AS cohort_name,
					ma.class_id,
					cl.name AS class_name,
					ma.assigned_at,
					ma.unassigned_at,
					ma.status
				FROM manager_assignment ma
				JOIN "class" cl ON cl.class_id = ma.class_id
				JOIN cohort c ON c.cohort_id = cl.cohort_id
				WHERE ma.manager_user_id IN (%s)
				ORDER BY
					CASE WHEN ma.unassigned_at IS NULL THEN 0 ELSE 1 END,
					LOWER(c.name),
					LOWER(COALESCE(cl.name, '')),
					ma.assigned_at DESC,
					ma.assignment_id
				""".formatted(placeholders);
		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new ManagerAssignmentRow(
						rs.getObject("assignment_id", UUID.class),
						rs.getObject("manager_user_id", UUID.class),
						rs.getString("role_scope"),
						rs.getObject("cohort_id", UUID.class),
						rs.getString("cohort_name"),
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name"),
						instant(rs, "assigned_at"),
						instant(rs, "unassigned_at"),
						rs.getString("status")
				),
				managerIds.toArray()
		);
	}

	@Override
	public Page<TraineeRow> findTrainees(TraineeCriteria criteria) {
		StringBuilder fromAndWhere = new StringBuilder("""
				FROM cohort_member cm
				JOIN app_user u ON u.user_id = cm.user_id
				JOIN "role" r ON r.role_id = u.role_id
				WHERE cm.cohort_id = ?
					AND cm.org_id = ?
					AND r.code = 'TRAINEE'
				""");
		List<Object> parameters = new ArrayList<>();
		parameters.add(criteria.cohortId());
		parameters.add(criteria.organizationId());
		appendAccountStatusFilter(fromAndWhere, parameters, criteria.status());
		appendQueryFilter(fromAndWhere, parameters, criteria.query());
		if (criteria.classroomId() != null) {
			fromAndWhere.append("""
					AND EXISTS (
						SELECT 1
						FROM class_membership selected_membership
						WHERE selected_membership.cohort_member_id = cm.cohort_member_id
							AND selected_membership.class_id = ?
							AND selected_membership.org_id = cm.org_id
							AND selected_membership.unassigned_at IS NULL
					)
					""");
			parameters.add(criteria.classroomId());
		}

		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) " + fromAndWhere,
				Long.class,
				parameters.toArray()
		);
		String sql = """
				SELECT
					cm.cohort_member_id,
					u.user_id,
					u.name,
					u.email,
					u.status,
					u.deleted_at,
					cm.status AS membership_status,
					cm.left_at
				""" + fromAndWhere + """
				ORDER BY LOWER(COALESCE(NULLIF(u.name, ''), u.email)), u.user_id
				LIMIT ? OFFSET ?
				""";
		List<Object> pageParameters = new ArrayList<>(parameters);
		pageParameters.add(criteria.size());
		pageParameters.add((long) criteria.page() * criteria.size());
		List<TraineeRow> content = jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new TraineeRow(
						rs.getObject("cohort_member_id", UUID.class),
						rs.getObject("user_id", UUID.class),
						rs.getString("name"),
						rs.getString("email"),
						rs.getString("status"),
						rs.getTimestamp("deleted_at") != null,
						rs.getString("membership_status"),
						instant(rs, "left_at")
				),
				pageParameters.toArray()
		);
		return new Page<>(content, total == null ? 0 : total);
	}

	@Override
	public List<CurrentClassroomRow> findCurrentClassrooms(List<UUID> cohortMemberIds) {
		if (cohortMemberIds.isEmpty()) {
			return List.of();
		}
		String placeholders = String.join(", ", cohortMemberIds.stream().map(id -> "?").toList());
		String sql = """
				SELECT
					membership.cohort_member_id,
					membership.class_id,
					cl.name AS class_name
				FROM class_membership membership
				JOIN "class" cl ON cl.class_id = membership.class_id
				WHERE membership.cohort_member_id IN (%s)
					AND membership.unassigned_at IS NULL
					AND cl.deleted_at IS NULL
				ORDER BY membership.assigned_at DESC, membership.class_membership_id DESC
				""".formatted(placeholders);
		return jdbcTemplate.query(
				sql,
				(rs, rowNum) -> new CurrentClassroomRow(
						rs.getObject("cohort_member_id", UUID.class),
						rs.getObject("class_id", UUID.class),
						rs.getString("class_name")
				),
				cohortMemberIds.toArray()
		);
	}

	private void appendRoleFilter(StringBuilder sql, List<Object> parameters, Role role) {
		if (role == null) {
			sql.append(" AND r.code IN ('OPERATOR', 'MANAGER')");
			return;
		}
		sql.append(" AND r.code = ?");
		parameters.add(role.name());
	}

	private void appendAccountStatusFilter(StringBuilder sql, List<Object> parameters, AccountStatus status) {
		if (status == null) {
			return;
		}
		if (status == AccountStatus.INACTIVE) {
			sql.append(" AND (u.status = 'INACTIVE' OR u.deleted_at IS NOT NULL)");
			return;
		}
		sql.append(" AND u.deleted_at IS NULL AND u.status = ?");
		parameters.add(status == AccountStatus.INVITED ? "PENDING" : status.name());
	}

	private void appendQueryFilter(StringBuilder sql, List<Object> parameters, String query) {
		if (query == null) {
			return;
		}
		sql.append(" AND (POSITION(? IN LOWER(COALESCE(u.name, ''))) > 0 OR POSITION(? IN LOWER(u.email)) > 0)");
		parameters.add(query);
		parameters.add(query);
	}

	private String managerOrderBy(ManagerCriteria criteria) {
		String direction = criteria.direction().name();
		if (criteria.sortBy() == MemberSortField.LAST_LOGIN_AT) {
			return " ORDER BY u.last_login_at " + direction + " NULLS LAST, u.user_id " + direction;
		}
		return " ORDER BY LOWER(COALESCE(NULLIF(u.name, ''), u.email)) " + direction + ", u.user_id " + direction;
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		Timestamp timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
