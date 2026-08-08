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
import java.util.Arrays;
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

	/**
	 * 플레이스홀더 순서가 곧 인자 순서라 {@link #selectArgs(UUID)}와 <b>반드시 짝을 맞춰야 한다</b>.
	 * 목적 4개 뒤에 기수 4개(담당 반·담당 인원 서브쿼리가 각각 2개씩)가 온다.
	 *
	 * <p>담당 반·담당 인원은 {@code ?::uuid IS NULL} 관용구로 기수를 건다. 기수를 안 넘기면 기관 전체가
	 * 되도록 인자 개수를 항상 고정해, 조건부로 인자를 넣고 빼다 순서가 밀리는 일을 없앤다.
	 */
	private static final String MANAGER_SELECT = """
			SELECT u.user_id AS manager_id,
			       u.name,
			       u.email,
			       u.status AS account_status,
			       u.last_login_at,
			       (SELECT ui.invited_at FROM one_time_token t
			         JOIN user_invitation ui ON ui.invitation_id = t.invitation_id
			         WHERE t.user_id = u.user_id AND t.purpose = ?
			         ORDER BY ui.invited_at ASC LIMIT 1) AS invited_at,
			       (SELECT inviter.name FROM one_time_token t
			         JOIN user_invitation ui ON ui.invitation_id = t.invitation_id
			         JOIN app_user inviter ON inviter.user_id = ui.invited_by
			         WHERE t.user_id = u.user_id AND t.purpose = ?
			         ORDER BY ui.invited_at ASC LIMIT 1) AS invited_by_name,
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
			           AND (?::uuid IS NULL OR c.cohort_id = ?::uuid)
			       ) AS classroom_names,
			       COALESCE((SELECT COUNT(DISTINCT cm.cohort_member_id) FROM manager_assignment ma
			         JOIN class c ON c.class_id = ma.class_id
			         JOIN class_membership csm ON csm.class_id = ma.class_id AND csm.unassigned_at IS NULL
			         JOIN cohort_member cm ON cm.cohort_member_id = csm.cohort_member_id AND cm.status = 'ACTIVE'
			         WHERE ma.manager_user_id = u.user_id AND ma.status = 'ACTIVE' AND ma.unassigned_at IS NULL
			           AND (?::uuid IS NULL OR c.cohort_id = ?::uuid)
			       ), 0) AS assigned_trainee_count
			FROM app_user u
			JOIN "role" r ON r.role_id = u.role_id AND r.code = 'MANAGER'
			""";

	/**
	 * 그 기수를 담당하는 매니저인지 판정한다. <b>담당 반 배정 OR 매니저 초대</b>인 이유는, 초대만 되고
	 * 아직 반이 없는 매니저(화면의 '가입 대기 · 미배정')도 그 기수 목록에 나와야 하기 때문이다.
	 * 배정만 보면 이 행이 통째로 사라지고, 초대만 보면 다른 기수로 초대됐다가 이 기수 반을 맡은
	 * 매니저가 빠진다.
	 *
	 * <p>앞 조건과 붙여 쓰지 않도록 <b>공백을 넣어 이어붙여야 한다</b>. 텍스트 블록은 들여쓰기 공백을
	 * 없애므로 여기에 선행 공백을 적어 두는 방식은 통하지 않는다({@code u.org_id = ?AND (EXISTS}).
	 */
	private static final String COHORT_SCOPE_CONDITION = """
			AND (EXISTS (SELECT 1 FROM manager_assignment ma
			               JOIN class c ON c.class_id = ma.class_id
			               WHERE ma.manager_user_id = u.user_id AND ma.status = 'ACTIVE'
			                 AND ma.unassigned_at IS NULL AND c.cohort_id = ?)
			      OR EXISTS (SELECT 1 FROM one_time_token t
			                  JOIN user_invitation ui ON ui.invitation_id = t.invitation_id
			                  WHERE t.user_id = u.user_id AND t.purpose = ?
			                    AND ui.target_cohort_id = ?))""";

	/**
	 * 상태별 인원은 목록과 <b>같은 모집단</b>(삭제되지 않은 이 기관의 MANAGER)을 쓰되 검색·상태
	 * 필터만 걸지 않는다. 필터를 함께 걸면 상태 칩이 자기 자신을 필터링해 항상 자기 개수만 남는다.
	 */
	@Override
	public Map<String, Long> countByStatus(UUID orgId, UUID cohortId) {
		Filter filter = new Filter(orgId, cohortId);
		String sql = """
				SELECT u.status AS account_status, COUNT(*) AS member_count
				FROM app_user u
				JOIN "role" r ON r.role_id = u.role_id AND r.code = 'MANAGER'
				"""
				+ filter.where()
				+ " GROUP BY u.status";
		Map<String, Long> counts = new LinkedHashMap<>();
		jdbcTemplate.query(sql, rs -> {
			counts.put(rs.getString("account_status"), rs.getLong("member_count"));
		}, filter.args().toArray());
		return counts;
	}

	@Override
	public Page<ManagerRosterRow> findManagers(ManagerRosterCriteria criteria, Pageable pageable) {
		Filter filter = new Filter(criteria.orgId(), criteria.cohortId());
		filter.addStatus(criteria.rawAccountStatus());
		filter.addQuery(criteria.query());

		Long total = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM app_user u JOIN \"role\" r ON r.role_id = u.role_id AND r.code = 'MANAGER'"
						+ filter.where(),
				Long.class, filter.args().toArray());

		List<Object> args = new ArrayList<>(selectArgs(criteria.cohortId()));
		args.addAll(filter.args());
		args.add(pageable.getPageSize());
		args.add(pageable.getOffset());

		List<ManagerRosterRow> content = jdbcTemplate.query(
				MANAGER_SELECT + filter.where() + orderBy(criteria.sort()) + " LIMIT ? OFFSET ?",
				(ResultSet rs, int rowNum) -> mapRow(rs),
				args.toArray());

		return new PageImpl<>(content, pageable, total == null ? 0 : total);
	}

	/**
	 * {@link #MANAGER_SELECT}의 플레이스홀더 순서와 짝을 맞춘 SELECT 절 인자.
	 * {@code cohortId}가 null이면 그대로 NULL로 바인딩되어 {@code ?::uuid IS NULL} 쪽이 참이 된다.
	 * null을 담아야 해서 {@code List.of}가 아니라 {@link Arrays#asList}를 쓴다.
	 */
	private List<Object> selectArgs(UUID cohortId) {
		return Arrays.asList(
				MANAGER_INVITE_PURPOSE,
				MANAGER_INVITE_PURPOSE,
				MANAGER_INVITE_PURPOSE,
				MANAGER_INVITE_PURPOSE,
				cohortId, cohortId,
				cohortId, cohortId);
	}

	/**
	 * WHERE 절과 인자를 <b>한 자리에서</b> 같이 쌓는다. 목록 쿼리와 COUNT 쿼리가 같은 인스턴스를 쓰므로
	 * 두 쿼리의 조건이 서로 어긋날 수 없다.
	 */
	private static final class Filter {
		private final StringBuilder where = new StringBuilder(" WHERE u.deleted_at IS NULL AND u.org_id = ?");
		private final List<Object> args = new ArrayList<>();

		private Filter(UUID orgId, UUID cohortId) {
			args.add(orgId);
			if (cohortId != null) {
				where.append(' ').append(COHORT_SCOPE_CONDITION);
				args.add(cohortId);
				args.add(MANAGER_INVITE_PURPOSE);
				args.add(cohortId);
			}
		}

		private void addStatus(String rawAccountStatus) {
			if (rawAccountStatus == null) {
				return;
			}
			where.append(" AND u.status = ?");
			args.add(rawAccountStatus);
		}

		private void addQuery(String query) {
			if (query == null || query.isBlank()) {
				return;
			}
			where.append(" AND (u.name ILIKE ? OR u.email ILIKE ?)");
			String likeQuery = "%" + query.trim() + "%";
			args.add(likeQuery);
			args.add(likeQuery);
		}

		private String where() {
			return where.toString();
		}

		private List<Object> args() {
			return args;
		}
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
				toInstant(rs.getTimestamp("invited_at")),
				rs.getString("invited_by_name")
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
