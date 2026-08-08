package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcManagerRosterRepository implements ManagerRosterRepository {

	/** {@code JdbcOrganizationOperatorRepository}의 동일 상수와 항상 같은 값이어야 한다(v06 초대 목적 통합). */
	private static final String MANAGER_INVITE_PURPOSE = "INVITE_OPERATOR_MANAGER";

	private final JdbcTemplate jdbcTemplate;

	private static final String MANAGER_SELECT = """
			SELECT u.user_id AS manager_id,
			       u.name,
			       u.email,
			       u.status AS account_status,
			       u.last_login_at,
			       (SELECT MIN(t.issued_at) FROM one_time_token t
			         WHERE t.user_id = u.user_id AND t.purpose = ?) AS invited_at,
			       (SELECT ui.target_cohort_id FROM one_time_token t
			         JOIN user_invitation ui ON ui.invitation_id = t.invitation_id
			         WHERE t.user_id = u.user_id AND t.purpose = ?
			         ORDER BY ui.invited_at DESC LIMIT 1) AS cohort_id,
			       (SELECT co.name FROM one_time_token t
			         JOIN user_invitation ui ON ui.invitation_id = t.invitation_id
			         JOIN cohort co ON co.cohort_id = ui.target_cohort_id
			         WHERE t.user_id = u.user_id AND t.purpose = ?
			         ORDER BY ui.invited_at DESC LIMIT 1) AS cohort_name,
			       (SELECT ARRAY_AGG(c.name ORDER BY c.name) FROM manager_assignment ma
			         JOIN class c ON c.class_id = ma.class_id
			         WHERE ma.manager_user_id = u.user_id AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
			       ) AS classroom_names,
			       COALESCE((SELECT COUNT(DISTINCT cm.cohort_member_id) FROM manager_assignment ma
			         JOIN class_membership csm ON csm.class_id = ma.class_id AND csm.unassigned_at IS NULL
			         JOIN cohort_member cm ON cm.cohort_member_id = csm.cohort_member_id AND cm.status = 'ACTIVE'
			         WHERE ma.manager_user_id = u.user_id AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
			       ), 0) AS assigned_trainee_count
			FROM app_user u
			JOIN "role" r ON r.role_id = u.role_id AND r.code = 'MANAGER'
			""";

	/**
	 * 상태별 인원은 목록과 <b>같은 모집단</b>(삭제되지 않은 이 기관의 MANAGER)을 쓰되 검색·상태
	 * 필터만 걸지 않는다. 필터를 함께 걸면 상태 칩이 자기 자신을 필터링해 항상 자기 개수만 남는다.
	 */
	@Override
	public Map<String, Long> countByStatus(UUID orgId) {
		String sql = """
				SELECT u.status AS account_status, COUNT(*) AS member_count
				FROM app_user u
				JOIN "role" r ON r.role_id = u.role_id AND r.code = 'MANAGER'
				WHERE u.deleted_at IS NULL AND u.org_id = ?
				GROUP BY u.status
				""";
		Map<String, Long> counts = new LinkedHashMap<>();
		jdbcTemplate.query(sql, rs -> {
			counts.put(rs.getString("account_status"), rs.getLong("member_count"));
		}, orgId);
		return counts;
	}

	@Override
	public Page<ManagerRosterRow> findManagers(ManagerRosterCriteria criteria, Pageable pageable) {
		StringBuilder where = new StringBuilder(" WHERE u.deleted_at IS NULL AND u.org_id = ?");
		List<Object> filterArgs = new ArrayList<>(List.of(criteria.orgId()));

		if (criteria.rawAccountStatus() != null) {
			where.append(" AND u.status = ?");
			filterArgs.add(criteria.rawAccountStatus());
		}
		if (criteria.query() != null && !criteria.query().isBlank()) {
			where.append(" AND (u.name ILIKE ? OR u.email ILIKE ?)");
			String likeQuery = "%" + criteria.query().trim() + "%";
			filterArgs.add(likeQuery);
			filterArgs.add(likeQuery);
		}

		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM app_user u JOIN \"role\" r ON r.role_id = u.role_id AND r.code = 'MANAGER'"
						+ where,
				Long.class, filterArgs.toArray());

		List<Object> selectArgs = new ArrayList<>(List.of(
				MANAGER_INVITE_PURPOSE, MANAGER_INVITE_PURPOSE, MANAGER_INVITE_PURPOSE));
		selectArgs.addAll(filterArgs);
		selectArgs.add(pageable.getPageSize());
		selectArgs.add(pageable.getOffset());

		List<ManagerRosterRow> content = jdbcTemplate.query(
				MANAGER_SELECT + where + orderBy(criteria.sort()) + " LIMIT ? OFFSET ?",
				(ResultSet rs, int rowNum) -> mapRow(rs),
				selectArgs.toArray());

		return new PageImpl<>(content, pageable, total == null ? 0 : total);
	}

	private String orderBy(ManagerRosterSort sort) {
		if (sort == ManagerRosterSort.ASSIGNED_TRAINEE_COUNT) {
			return " ORDER BY assigned_trainee_count DESC, u.name ASC";
		}
		return " ORDER BY u.name ASC, u.email ASC";
	}

	private ManagerRosterRow mapRow(ResultSet rs) throws SQLException {
		return new ManagerRosterRow(
				rs.getObject("manager_id", UUID.class),
				rs.getString("name"),
				rs.getString("email"),
				rs.getString("account_status"),
				rs.getObject("cohort_id", UUID.class),
				rs.getString("cohort_name"),
				toStringList(rs.getArray("classroom_names")),
				rs.getLong("assigned_trainee_count"),
				toInstant(rs.getTimestamp("last_login_at")),
				toInstant(rs.getTimestamp("invited_at"))
		);
	}

	private List<String> toStringList(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		Object[] elements = (Object[]) array.getArray();
		List<String> names = new ArrayList<>(elements.length);
		for (Object element : elements) {
			names.add((String) element);
		}
		return names;
	}

	private Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
